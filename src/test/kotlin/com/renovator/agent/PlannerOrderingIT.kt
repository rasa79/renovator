package com.renovator.agent

import com.renovator.agent.actions.LlmActions
import com.renovator.agent.actions.LlmOutcome
import com.renovator.agent.llm.LlmChannel
import com.renovator.audit.AgentTrace
import com.renovator.domain.ChangeScope
import com.renovator.domain.DependencyTarget
import com.renovator.domain.PlanStep
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import com.renovator.domain.VersionChange
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * D9 ordering proof on the @State machine (PLAN §6): with a scripted (mock) LLM,
 * the planner runs the CHEAP validators before any sandbox build.
 */
class PlannerOrderingIT {
    private class ScriptedLlm : LlmActions() {
        override fun proposePlan(
            context: com.embabel.agent.api.common.OperationContext,
            repoModel: com.renovator.domain.RepoModel,
            goal: UpgradeGoal,
        ): LlmOutcome<UpgradePlan> =
            LlmOutcome.Accepted(
                UpgradePlan(
                    steps =
                        listOf(
                            PlanStep.VersionStep(
                                VersionChange("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0", ChangeScope.DIRECT),
                            ),
                        ),
                    rationale = "single bump",
                ),
                emptyList(),
            )

        override fun diagnoseFailure(
            context: com.embabel.agent.api.common.OperationContext,
            build: com.renovator.domain.BuildResult,
        ): LlmOutcome<com.renovator.domain.BuildDiagnosis> = error("n/a")

        override fun proposePatch(
            context: com.embabel.agent.api.common.OperationContext,
            diagnosis: com.renovator.domain.BuildDiagnosis,
            fileContent: String,
        ): LlmOutcome<com.renovator.domain.CodePatch> = error("n/a")
    }

    @Test
    fun `on the happy path, cheap validators run before any sandbox build`() {
        AgentTrace.clear()
        LlmChannel.actions = ScriptedLlm()
        try {
            val goal = UpgradeGoal(targets = listOf(DependencyTarget("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0")))
            val runRequest = RunRequest(repoPath = Path.of("fixtures/fixture-clean"), goal = goal)
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
                        mapOf("goal" to goal, "runRequest" to runRequest),
                    ).run()
            process.resultOfType(com.renovator.domain.UpgradeComplete::class.java)

            val order = AgentTrace.snapshot()
            assertTrue(order.first() == "analyzeRepository", "analysis must come first: $order")
            val validateIdx = order.indexOf("validatePlan")
            val runBuildIdx = order.indexOf("runBuild")
            assertTrue(validateIdx >= 0 && runBuildIdx >= 0, "both actions must have run: $order")
            assertTrue(validateIdx < runBuildIdx, "cheap validator must precede the sandbox build: $order")
            assertTrue(order.count { it == "runBuild" } == 1, "exactly one sandbox build expected: $order")
            assertTrue(order.last() == "finalizeUpgrade", "the loop must end by finalizing: $order")
        } finally {
            LlmChannel.actions = LlmActions()
        }
    }
}
