package com.renovator.agent

import com.renovator.agent.actions.LlmActions
import com.renovator.agent.actions.LlmOutcome
import com.renovator.agent.llm.LlmChannel
import com.renovator.api.RunService
import com.renovator.config.JacksonConfig
import com.renovator.config.RenovatorProperties
import com.renovator.domain.BuildDiagnosis
import com.renovator.domain.BuildResult
import com.renovator.domain.CodePatch
import com.renovator.domain.DependencyTarget
import com.renovator.domain.HumanDecision
import com.renovator.domain.RepoModel
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * HITL approval gates (PLAN Task 5.3, D11) — the WaitFor programmatic-submission
 * check (C-6) end to end, through the REAL service path:
 *
 *  - the run PARKS at the commit-candidate gate (WaitFor, WAITING);
 *  - RunService.submitDecision (the controller's call) TERMINATES the parked
 *    shell and re-seeds the gate + decision — the continuation reuses the SAME
 *    run id (one trajectory story, Resumed marker, no repeated Analyze);
 *  - approved -> Done -> UpgradeComplete; rejected -> Repairing carrying the
 *    human comment as the failure signal;
 *  - disarmed by config -> straight to Done, no park, no GatePending.
 *
 * KL-09 disposition: this is the C-6 fallback (Embabel 1.5.1 has no public
 * programmatic WaitFor submission — the phase-5 report quotes the evidence and
 * kl-09 flips to PERMANENT).
 */
class ApprovalGateIT {
    private val runId = "gate-demo"

    private fun goal() = UpgradeGoal(targets = listOf(DependencyTarget("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0")))

    private fun cannedPlan(): UpgradePlan =
        JacksonConfig()
            .proposalObjectMapper()
            .readValue(Path.of("eval/canned/fixture-clean/propose_plan.json").toFile(), UpgradePlan::class.java)

    private class ScriptedLlm : LlmActions() {
        override fun proposePlan(
            context: com.embabel.agent.api.common.OperationContext,
            repoModel: RepoModel,
            goal: UpgradeGoal,
            lastFailure: BuildDiagnosis?,
        ): LlmOutcome<UpgradePlan> = LlmOutcome.Accepted(ScriptedLlm.canned, emptyList())

        override fun diagnoseFailure(
            context: com.embabel.agent.api.common.OperationContext,
            build: BuildResult,
        ): LlmOutcome<BuildDiagnosis> = error("n/a for fixture-clean")

        override fun proposePatch(
            context: com.embabel.agent.api.common.OperationContext,
            diagnosis: BuildDiagnosis,
            fileContent: String,
        ): LlmOutcome<CodePatch> = error("n/a for fixture-clean")

        companion object {
            lateinit var canned: UpgradePlan
        }
    }

    private fun platform() =
        com.embabel.agent.test.integration.IntegrationTestUtils
            .dummyAgentPlatform()

    private fun metadata(approvals: RenovatorProperties.Approvals) =
        com.embabel.agent.api.annotation.support
            .AgentMetadataReader()
            .createAgentMetadata(
                RenovatorAgent(renovatorProperties = RenovatorProperties(approvals = approvals)),
            ) as com.embabel.agent.core.Agent

    private fun service(approvals: RenovatorProperties.Approvals): RunService =
        RunService(
            platform(),
            explicitAgent = metadata(approvals),
            repository = com.renovator.persistence.JsonFileAgentProcessRepository(),
        )

    private fun await(
        condition: () -> Boolean,
        message: String,
        timeoutSeconds: Int = 240,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(100)
        }
        error(message)
    }

    private fun armedApprovals() = RenovatorProperties.Approvals(plan = false, commitCandidate = true)

    @Test
    fun `process parks at the commit-candidate gate until approved, then finalizes`() {
        ScriptedLlm.canned = cannedPlan()
        LlmChannel.actions = ScriptedLlm()
        try {
            val svc = service(armedApprovals())
            val runId = svc.submit(goal(), RunRequest(Path.of("fixtures/fixture-clean"), goal()))
            await(
                { svc.pendingDecision(runId) != null },
                "the run never parked at the gate: ${svc.status(runId)}",
            )

            // The real path: the decision layer reads the pending payload and
            // submits the decision through RunService (DecisionController's call).
            val pending = svc.pendingDecision(runId)!!
            assertTrue(pending.kind == "approval" && pending.question.contains("commit-candidate"), pending.toString())
            svc.submitDecision(runId, HumanDecision(approved = true, comment = "go"))

            await(
                { svc.trajectory(runId).any { it.contains("\"terminal\":\"UpgradeComplete\"") } },
                "the continuation never completed: ${svc.status(runId)}",
            )
            val lines = svc.trajectory(runId)
            assertEquals(1, lines.count { it.contains("\"stage\":\"Analyzing\"") }, "no repeated Analyze")
            assertTrue(lines.any { it.contains("Resumed") && it.contains("human decision") }, "the decision resume marker")
            assertEquals(1, lines.count { it.contains("\"stage\":\"GatePending\"") }, "one park")
        } finally {
            LlmChannel.actions = LlmActions()
            com.renovator.audit.RunAudit
                .clear()
        }
    }

    @Test
    fun `rejection routes to Repairing with the human comment on the blackboard`() {
        ScriptedLlm.canned = cannedPlan()
        LlmChannel.actions = ScriptedLlm()
        try {
            val svc = service(armedApprovals())
            val runId = svc.submit(goal(), RunRequest(Path.of("fixtures/fixture-clean"), goal()))
            await(
                { svc.pendingDecision(runId) != null },
                "the run never parked at the gate: ${svc.status(runId)}",
            )
            svc.submitDecision(runId, HumanDecision(approved = false, comment = "this upgrade is not acceptable yet"))
            await(
                { svc.trajectory(runId).any { it.contains("\"stage\":\"Repairing\"") } },
                "the rejection never routed to Repairing: ${svc.status(runId)}",
            )
            val lines = svc.trajectory(runId)
            assertTrue(
                lines.any { it.contains("Resumed") && it.contains("rejected") && it.contains("not acceptable") },
                "the human comment rides the continuation marker:\n${lines.filter {
                    it.contains(
                        "Resumed",
                    ) || it.contains("Repairing")
                }.take(3)}",
            )
        } finally {
            LlmChannel.actions = LlmActions()
            com.renovator.audit.RunAudit
                .clear()
        }
    }

    @Test
    fun `gate disarmed by config means no park`() {
        ScriptedLlm.canned = cannedPlan()
        LlmChannel.actions = ScriptedLlm()
        try {
            val svc = service(RenovatorProperties.Approvals(plan = false, commitCandidate = false))
            val runId = svc.submit(goal(), RunRequest(Path.of("fixtures/fixture-clean"), goal()))
            await(
                { svc.trajectory(runId).any { it.contains("\"terminal\":\"UpgradeComplete\"") } },
                "the disarmed run never completed: ${svc.status(runId)}",
            )
            val lines = svc.trajectory(runId)
            assertTrue(lines.none { it.contains("\"stage\":\"GatePending\"") }, "no park: $lines")
            assertTrue(lines.none { it.contains("Resumed") }, "no decision resume")
            assertNotNull(svc, "sanity: service alive")
        } finally {
            LlmChannel.actions = LlmActions()
            com.renovator.audit.RunAudit
                .clear()
        }
    }
}
