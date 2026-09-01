package com.renovator.agent

import com.renovator.agent.actions.LlmActions
import com.renovator.agent.actions.LlmOutcome
import com.renovator.agent.llm.LlmChannel
import com.renovator.audit.AgentTrace
import com.renovator.audit.RunAudit
import com.renovator.audit.TrajectoryStore
import com.renovator.config.JacksonConfig
import com.renovator.config.ProcessOptionsFactory
import com.renovator.config.RenovatorProperties
import com.renovator.domain.BuildDiagnosis
import com.renovator.domain.BuildResult
import com.renovator.domain.CodePatch
import com.renovator.domain.DependencyTarget
import com.renovator.domain.RepoModel
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeBlocker
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Honest termination (PLAN Task 4.4, C-7) on fixture-no-path: the target version
 * 99.99.99 404s forever, so EVERY plan is rejected at L3 (version-exists). The
 * attempt ledger rides the Planning state; once `renovator.budget.max-attempts`
 * is hit the 0.00-cost escalation opens, produces the UpgradeBlocker (every
 * attempt + its typed rejection), transitions to Blocked and parks (WaitFor).
 * Applying is never visited; the framework's maxActions/ON_STUCK policy is the
 * backstop (verified in the phase-4 report, LEARN[014]).
 */
class TerminationIT {
    private fun goal(): UpgradeGoal =
        UpgradeGoal(targets = listOf(DependencyTarget("org.apache.commons", "commons-lang3", "3.12.0", "99.99.99")))

    private val runRequest = RunRequest(repoPath = Path.of("fixtures/fixture-no-path"), goal = goal())

    private fun cannedPlan(): UpgradePlan =
        JacksonConfig()
            .proposalObjectMapper()
            .readValue(Path.of("eval/canned/fixture-no-path/propose_plan.json").toFile(), UpgradePlan::class.java)

    private class ScriptedLlm : LlmActions() {
        var proposes = 0

        override fun proposePlan(
            context: com.embabel.agent.api.common.OperationContext,
            repoModel: RepoModel,
            goal: UpgradeGoal,
            lastFailure: BuildDiagnosis?,
        ): LlmOutcome<UpgradePlan> {
            proposes += 1
            return LlmOutcome.Accepted(ScriptedLlm.canned, emptyList())
        }

        override fun diagnoseFailure(
            context: com.embabel.agent.api.common.OperationContext,
            build: BuildResult,
        ): LlmOutcome<BuildDiagnosis> = error("no build ever runs: L3 rejects before Applying")

        override fun proposePatch(
            context: com.embabel.agent.api.common.OperationContext,
            diagnosis: BuildDiagnosis,
            fileContent: String,
        ): LlmOutcome<CodePatch> = error("no build ever runs: L3 rejects before Applying")

        companion object {
            lateinit var canned: UpgradePlan
        }
    }

    @Test
    fun `agent exhausts plan space and terminates in Blocked with an UpgradeBlocker`() {
        val scripted = ScriptedLlm().apply { ScriptedLlm.canned = cannedPlan() }
        AgentTrace.clear()
        RunAudit.clear()
        RunAudit.runId = "termination-it"
        Files.deleteIfExists(Path.of("var/runs/termination-it/trajectory.jsonl"))
        LlmChannel.actions = scripted
        try {
            val meta =
                com.embabel.agent.api.annotation.support.AgentMetadataReader().createAgentMetadata(
                    RenovatorAgent(),
                ) as com.embabel.agent.core.Agent
            val ap =
                com.embabel.agent.test.integration.IntegrationTestUtils
                    .dummyAgentPlatform()
            val process =
                ap
                    .createAgentProcess(
                        meta,
                        ProcessOptionsFactory().processOptions(com.embabel.agent.api.common.PlannerType.GOAP),
                        mapOf("goal" to goal(), "runRequest" to runRequest),
                    ).run()

            val order = AgentTrace.snapshot()
            // Every rejection looped back into Planning: five proposals, five L3
            // rejections, then the escalation (0.00 cost) and the WaitFor park.
            assertEquals(5, scripted.proposes, "one proposal per attempt: $order")
            assertEquals(1, order.count { it == "exhaustPlanSpace" }, "escalated once: $order")
            assertEquals(1, order.count { it == "requestHumanDecision" }, "parked at the human decision: $order")
            assertTrue(order.first() == "analyzeRepository", "$order")
            assertTrue(order.last() == "requestHumanDecision", "$order")
            assertTrue(order.count { it.startsWith("validatePlan:REJECTED") } == 5, "five L3 rejections: $order")
            // The framework backstop: never more than the configured action budget.
            assertTrue(order.size <= 25, "actions within budget: ${order.size}")

            // The blocker carries every attempt with its typed rejection; Applying
            // never appears (the fixture's mustNotVisitStages).
            val lines = TrajectoryStore().read("termination-it")
            val rejected = lines.count { it.contains("\"eventType\":\"ValidationOutcome\"") && it.contains("\"accepted\":false") }
            assertEquals(5, rejected, "five L3 rejection outcomes")
            val blockerLine = lines.first { it.contains("\"eventType\":\"ProposalReceived\"") && it.contains("UpgradeBlocker") }
            assertTrue(blockerLine.contains("5 attempt(s)"), "blocker names the attempt count: $blockerLine")
            assertTrue(lines.any { it.contains("\"eventType\":\"Escalated\"") }, "escalation recorded")
            assertTrue(lines.any { it.contains("L3:version-exists") }, "typed L3 rejection in the trajectory")
            assertTrue(lines.none { it.contains("\"stage\":\"Applying\"") }, "Applying never entered")
            assertTrue(lines.any { it.contains("\"stage\":\"Blocked\"") }, "Blocked entered")
        } finally {
            LlmChannel.actions = LlmActions()
            RunAudit.clear()
        }
    }

    @Test
    fun `lowering maxActions still terminates cleanly with a typed blocker`() {
        val scripted = ScriptedLlm().apply { ScriptedLlm.canned = cannedPlan() }
        AgentTrace.clear()
        RunAudit.clear()
        RunAudit.runId = "budget-it"
        Files.deleteIfExists(Path.of("var/runs/budget-it/trajectory.jsonl"))
        LlmChannel.actions = scripted
        try {
            // The framework safety net fires before the agent's own escalation can
            // finish: maxActions 5 (through the exhaust action) + maxAttempts 1
            // (escalate after a single L3 rejection) — the run must end TYPED
            // (TERMINATED by the EarlyTerminationPolicy, never a hang or a crash)
            // with the blocker already recorded in the trajectory.
            val factory =
                ProcessOptionsFactory(
                    RenovatorProperties(
                        budget = RenovatorProperties.Budget(maxActions = 5, maxAttempts = 1),
                    ),
                )
            val meta =
                com.embabel.agent.api.annotation.support.AgentMetadataReader().createAgentMetadata(
                    RenovatorAgent(),
                ) as com.embabel.agent.core.Agent
            val ap =
                com.embabel.agent.test.integration.IntegrationTestUtils
                    .dummyAgentPlatform()
            val process =
                ap
                    .createAgentProcess(
                        meta,
                        factory.processOptions(com.embabel.agent.api.common.PlannerType.GOAP),
                        mapOf("goal" to goal(), "runRequest" to runRequest),
                    ).run()

            val order = AgentTrace.snapshot()
            assertTrue(order.size <= 5, "the framework budget is a hard ceiling: ${order.size}")
            assertTrue(
                process.status in
                    setOf(
                        com.embabel.agent.core.AgentProcessStatusCode.TERMINATED,
                        com.embabel.agent.core.AgentProcessStatusCode.WAITING,
                    ),
                "typed end state: ${process.status}",
            )
            val lines = TrajectoryStore().read("budget-it")
            assertTrue(
                lines.any { it.contains("\"eventType\":\"ProposalReceived\"") && it.contains("UpgradeBlocker") } ||
                    lines.any { it.contains("\"accepted\":false") },
                "the ledger (or its first rejection) reached the trajectory:\n${lines.takeLast(4)}",
            )
        } finally {
            LlmChannel.actions = LlmActions()
            RunAudit.clear()
        }
    }
}
