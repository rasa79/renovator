package com.renovator.eval

import com.renovator.agent.actions.LlmActions
import com.renovator.agent.actions.LlmOutcome
import com.renovator.agent.llm.LlmChannel
import com.renovator.audit.RunAudit
import com.renovator.config.JacksonConfig
import com.renovator.domain.BuildDiagnosis
import com.renovator.domain.BuildResult
import com.renovator.domain.CodePatch
import com.renovator.domain.DependencyTarget
import com.renovator.domain.RepoModel
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The mock-eval harness (PLAN Task 6.1): drives one fixture with its canned LLM
 * (from `eval/canned/<fixture>/`) and returns the run's trajectory facts for the
 * runner's comparison. Lives in the test tree because the dummy platform is
 * test-scoped; EvalRunner's comparison/report logic is production.
 */
object EvalHarness {
    private class CannedLlm(
        private val plans: ArrayDeque<UpgradePlan>,
        private val diagnosis: BuildDiagnosis?,
        private val patch: CodePatch?,
    ) : LlmActions() {
        override fun proposePlan(
            context: com.embabel.agent.api.common.OperationContext,
            repoModel: RepoModel,
            goal: UpgradeGoal,
            lastFailure: BuildDiagnosis?,
        ): LlmOutcome<UpgradePlan> {
            // A single canned plan answers every proposal (the no-path loop);
            // two plans serve the two-hop fixture (direct, then the replan).
            val next = if (plans.size == 1) plans.first() else plans.removeFirst()
            return LlmOutcome.Accepted(next, emptyList())
        }

        override fun diagnoseFailure(
            context: com.embabel.agent.api.common.OperationContext,
            build: BuildResult,
        ): LlmOutcome<BuildDiagnosis> = LlmOutcome.Accepted(requireNotNull(diagnosis) { "no canned diagnosis" }, emptyList())

        override fun proposePatch(
            context: com.embabel.agent.api.common.OperationContext,
            diagnosis: BuildDiagnosis,
            fileContent: String,
        ): LlmOutcome<CodePatch> = LlmOutcome.Accepted(requireNotNull(patch) { "no canned patch" }, emptyList())
    }

    private fun cannedLlm(fixture: String): CannedLlm {
        val dir = Path.of("eval/canned", fixture)
        val plans = ArrayDeque<UpgradePlan>()
        val single = dir.resolve("propose_plan.json")
        if (java.nio.file.Files
                .exists(single)
        ) {
            plans += readPlan(single)
        }
        val direct = dir.resolve("propose_plan_direct.json")
        val replan = dir.resolve("propose_plan_replan.json")
        if (java.nio.file.Files
                .exists(direct)
        ) {
            plans += readPlan(direct)
        }
        if (java.nio.file.Files
                .exists(replan)
        ) {
            plans += readPlan(replan)
        }
        val diagnosis =
            dir
                .resolve("diagnose.json")
                .takeIf {
                    java.nio.file.Files
                        .exists(it)
                }?.let { readDiagnosis(it) }
        val patch =
            dir
                .resolve("propose_patch.json")
                .takeIf {
                    java.nio.file.Files
                        .exists(it)
                }?.let { readPatch(it) }
        return CannedLlm(plans, diagnosis, patch)
    }

    private fun readPlan(path: Path): UpgradePlan = JacksonConfig().proposalObjectMapper().readValue(path.toFile(), UpgradePlan::class.java)

    private fun readDiagnosis(path: Path): BuildDiagnosis =
        JacksonConfig().proposalObjectMapper().readValue(path.toFile(), BuildDiagnosis::class.java)

    private fun readPatch(path: Path): CodePatch = JacksonConfig().proposalObjectMapper().readValue(path.toFile(), CodePatch::class.java)

    private fun goalFor(fixture: String): UpgradeGoal {
        val outcome = ExpectedOutcomeLoader.load(Path.of("fixtures", fixture, "expected-outcome.yml"))
        return UpgradeGoal(
            targets =
                outcome.goal.targets.map {
                    DependencyTarget(it.groupId, it.artifactId, it.fromVersion, it.toVersion)
                },
        )
    }

    /** Run one fixture with its canned LLM; return the trajectory facts. */
    fun runFixture(fixture: String): EvalRunner.RunResult {
        val runId = "eval-$fixture"
        java.nio.file.Files
            .deleteIfExists(Path.of("var/runs/$runId/trajectory.jsonl"))
        RunAudit.clear()
        RunAudit.runId = runId
        LlmChannel.actions = cannedLlm(fixture)
        try {
            val meta =
                com.embabel.agent.api.annotation.support
                    .AgentMetadataReader()
                    .createAgentMetadata(com.renovator.agent.RenovatorAgent()) as com.embabel.agent.core.Agent
            val ap =
                com.embabel.agent.test.integration.IntegrationTestUtils
                    .dummyAgentPlatform()
            val goal = goalFor(fixture)
            val process =
                ap
                    .createAgentProcess(
                        meta,
                        com.renovator.config
                            .ProcessOptionsFactory()
                            .processOptions(com.embabel.agent.api.common.PlannerType.GOAP),
                        mapOf("goal" to goal, "runRequest" to RunRequest(Path.of("fixtures", fixture), goal)),
                    ).run()
            val lines =
                com.renovator.audit
                    .TrajectoryStore()
                    .read(runId)
            val stages =
                lines.mapNotNull { Regex(""""stage":"([A-Za-z]+)"""").find(it)?.groupValues?.get(1) }
            val attempts = lines.count { it.contains("\"eventType\":\"PlanAttempted\"") }
            val terminal =
                when {
                    lines.any { it.contains("\"terminal\":\"UpgradeComplete\"") } -> "UpgradeComplete"
                    lines.any { it.contains("\"kind\":\"UpgradeBlocker\"") } -> "UpgradeBlocker"
                    else -> error("run $runId has no terminal: ${lines.takeLast(3)}")
                }
            return EvalRunner.RunResult(runId = runId, terminal = terminal, stages = stages, attempts = attempts)
        } finally {
            LlmChannel.actions = LlmActions()
            RunAudit.clear()
        }
    }
}
