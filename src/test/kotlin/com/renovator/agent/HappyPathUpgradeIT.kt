package com.renovator.agent

import com.renovator.agent.actions.LlmActions
import com.renovator.agent.actions.LlmOutcome
import com.renovator.agent.llm.LlmChannel
import com.renovator.audit.AgentTrace
import com.renovator.domain.ChangeScope
import com.renovator.domain.DependencyTarget
import com.renovator.domain.PlanStep
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeComplete
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import com.renovator.domain.ValidationRejection
import com.renovator.domain.VersionChange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Mock-LLM happy path (PLAN Task 3.4/4.1, D13) on the @State machine: the FULL
 * loop with a SCRIPTED LLM — proposal -> L1-L3 validation -> Validated*
 * construction -> executor acceptance -> judge verdict on fixture-clean — plus
 * the surviving-a-bad-answer proof (reviewer mandate): a garbage answer becomes a
 * typed L0 rejection (reason surfaced), the loop replans and completes.
 */
class HappyPathUpgradeIT {
    private fun goal() = UpgradeGoal(targets = listOf(DependencyTarget("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0")))

    private val runRequest = RunRequest(repoPath = Path.of("fixtures/fixture-clean"), goal = goal())
    private val cannedPlan =
        UpgradePlan(
            steps =
                listOf(
                    PlanStep.VersionStep(VersionChange("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0", ChangeScope.DIRECT)),
                ),
            rationale = "single bump",
        )

    private class ScriptedLlm(
        private val outcomes: ArrayDeque<LlmOutcome<UpgradePlan>> = ArrayDeque(),
    ) : LlmActions() {
        fun enqueue(outcome: LlmOutcome<UpgradePlan>) {
            outcomes.addLast(outcome)
        }

        override fun proposePlan(
            context: com.embabel.agent.api.common.OperationContext,
            repoModel: com.renovator.domain.RepoModel,
            goal: UpgradeGoal,
        ): LlmOutcome<UpgradePlan> {
            val next = outcomes.removeFirst()
            return if (next is LlmOutcome.Accepted) {
                LlmOutcome.Accepted(canned, next.attempts)
            } else {
                next
            }
        }

        override fun diagnoseFailure(
            context: com.embabel.agent.api.common.OperationContext,
            build: com.renovator.domain.BuildResult,
        ): LlmOutcome<com.renovator.domain.BuildDiagnosis> {
            error("not scripted for happy path")
        }

        override fun proposePatch(
            context: com.embabel.agent.api.common.OperationContext,
            diagnosis: com.renovator.domain.BuildDiagnosis,
            fileContent: String,
        ): LlmOutcome<com.renovator.domain.CodePatch> {
            error("not scripted for happy path")
        }

        companion object {
            lateinit var canned: UpgradePlan
        }
    }

    private fun runUpgrade(scripted: ScriptedLlm): List<String> {
        AgentTrace.clear()
        ScriptedLlm.canned = cannedPlan
        LlmChannel.actions = scripted
        return try {
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
                        com.embabel.agent.core.ProcessOptions.DEFAULT
                            .withPlannerType(com.embabel.agent.api.common.PlannerType.GOAP),
                        mapOf("goal" to goal(), "runRequest" to runRequest),
                    ).run()
            process.resultOfType(UpgradeComplete::class.java)
            AgentTrace.snapshot()
        } finally {
            LlmChannel.actions = LlmActions()
        }
    }

    @Test
    fun `fixture-clean upgrade reaches UpgradeComplete with exactly one build`() {
        val scripted = ScriptedLlm()
        scripted.enqueue(LlmOutcome.Accepted(cannedPlan, emptyList()))
        val order = runUpgrade(scripted)
        assertTrue(order.first() == "analyzeRepository", "full loop starts with analysis: $order")
        assertTrue(order.contains("proposeUpgradePlan"), "the LLM proposal ran: $order")
        assertTrue(order.count { it == "runBuild" } == 1, "exactly one sandbox build: $order")
        assertTrue(order.last() == "finalizeUpgrade", "$order")
    }

    @Test
    fun `a bad LLM answer is rejected and the run survives`() {
        val scripted = ScriptedLlm()
        scripted.enqueue(
            LlmOutcome.Rejected(
                ValidationRejection("L0:binding", "llm output failed typed binding: this is not json {{{", "not json {{{"),
                emptyList(),
            ),
        )
        scripted.enqueue(LlmOutcome.Accepted(cannedPlan, emptyList()))
        val order = runUpgrade(scripted)

        val rejected = order.filter { it.contains("REJECTED") }
        assertEquals(1, rejected.size, "exactly one rejection: $rejected")
        assertTrue(rejected.single().contains("L0:binding"), "typed rejection: ${rejected.single()}")
        assertTrue(rejected.single().contains("not json"), "reason surfaced: ${rejected.single()}")
        assertEquals(2, order.count { it == "proposeUpgradePlan" }, "two propose attempts: $order")
        assertTrue(order.last() == "finalizeUpgrade", "$order")
    }
}
