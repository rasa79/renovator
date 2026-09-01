package com.renovator.agent

import com.embabel.agent.api.invocation.AgentInvocation
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.renovator.audit.AgentTrace
import com.renovator.domain.ChangeScope
import com.renovator.domain.DependencyTarget
import com.renovator.domain.PlanStep
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeComplete
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import com.renovator.domain.VersionChange
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * D9 ordering proof (PLAN §6, Task 3.3): on the happy path with a scripted (mock)
 * LLM, the planner runs the CHEAP validators before any sandbox build — the cost
 * asymmetry routes the plan (cheap checks gate every proposal; the sandbox is only
 * reached when everything cheap has passed).
 */
class PlannerOrderingIT {
    @Test
    fun `on the happy path, cheap validators run before any sandbox build`() {
        AgentTrace.clear()

        val goal = UpgradeGoal(targets = listOf(DependencyTarget("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0")))
        val runRequest = RunRequest(repoPath = Path.of("fixtures/fixture-clean"), goal = goal)

        // Dummy-platform ordering proof with the plan pre-seeded (the LLM action is
        // not part of this assertion; scripted LLM mocking lives in HappyPathUpgradeIT).
        // Chosen after empirical finding: the Spring mock base executes the flow via
        // its mocked LLM operations WITHOUT invoking the @Action method bodies (Phase-3
        // report note); the trace therefore belongs to a real planner execution.
        val meta =
            com.embabel.agent.api.annotation.support.AgentMetadataReader().createAgentMetadata(
                com.renovator.agent.RenovatorAgent(
                    com.renovator.agent.actions
                        .AnalyzeRepositoryAction(),
                    com.renovator.agent.actions
                        .RunBuildAction(),
                    com.renovator.agent.actions
                        .ApplyValidatedChangesAction(),
                    com.renovator.agent.actions
                        .FinalizeUpgradeAction(),
                    com.renovator.agent.actions
                        .ValidatePlanAction(),
                    com.renovator.agent.actions
                        .ValidatePatchAction(),
                    com.renovator.agent.actions
                        .DryRunCompileAction(),
                    com.renovator.agent.actions
                        .RequestHumanDecisionAction(),
                    com.renovator.agent.actions
                        .LlmActions(),
                ),
            ) as com.embabel.agent.core.Agent
        val ap =
            com.embabel.agent.test.integration.IntegrationTestUtils
                .dummyAgentPlatform()
        val plan =
            UpgradePlan(
                steps =
                    listOf(
                        PlanStep.VersionStep(VersionChange("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0", ChangeScope.DIRECT)),
                    ),
                rationale = "single bump",
            )
        val process =
            ap
                .createAgentProcess(
                    meta,
                    ProcessOptions(plannerType = com.embabel.agent.api.common.PlannerType.GOAP),
                    mapOf("goal" to goal, "runRequest" to runRequest, "plan" to plan),
                ).run()
        val result = process.resultOfType(UpgradeComplete::class.java)
        assertNotNull(result, "the happy path must reach UpgradeComplete")

        val order = AgentTrace.snapshot()
        // The plan is pre-seeded for this harness (LLM action not mocked here), so the
        // trace starts at validation — the ORDERING claim is validated < build.
        assertTrue(order.first() == "validatePlan", "validation must come first: $order")
        val validateIdx = order.indexOf("validatePlan")
        val runBuildIdx = order.indexOf("runBuild")
        assertTrue(validateIdx >= 0 && runBuildIdx >= 0, "both actions must have run: $order")
        assertTrue(validateIdx < runBuildIdx, "cheap validator must precede the sandbox build: $order")
        assertTrue(order.count { it == "runBuild" } == 1, "exactly one sandbox build expected: $order")
        assertTrue(order.last() == "finalizeUpgrade", "the loop must end by finalizing: $order")
    }
}
