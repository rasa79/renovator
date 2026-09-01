package com.renovator.agent

import com.renovator.agent.actions.LlmActions
import com.renovator.agent.actions.LlmOutcome
import com.renovator.agent.llm.LlmChannel
import com.renovator.api.RunService
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
import com.renovator.domain.UpgradeComplete
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import com.renovator.persistence.JsonFileAgentProcessRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Kill-and-resume (PLAN Task 4.5, D10) on fixture-api-removal.
 *
 * phaseKill: the upgrade runs with maxActions=4 — the run is CUT MID-FLIGHT
 * (the framework's early termination, the SIGKILL equivalent: the process stops
 * while the machine is inside the Applying frame, no graceful completion, no
 * finalize, no Done) — and the typed snapshot lands on disk.
 *
 * phaseResume: a FRESH process re-seeds from the snapshot (RunService.resume)
 * and the continuation runs: apply -> build fails (the migration breakage) ->
 * repair (canned) -> apply -> green -> UpgradeComplete — same run id, same
 * trajectory: one Resumed marker, NO repeated Analyze stage (the freshRun gate).
 *
 * scripts/demo-kill-resume.sh executes these two phases as separate JVMs
 * (#phaseKill then #phaseResume): the first JVM's run state exists only in the
 * snapshot when the second one starts.
 */
@TestMethodOrder(MethodOrderer.MethodName::class)
class KillResumeIT {
    private val runId = "kill-demo"

    private fun goal(): UpgradeGoal =
        UpgradeGoal(targets = listOf(DependencyTarget("org.apache.commons", "commons-lang3", "2.6", "3.14.0")))

    private val runRequest = RunRequest(repoPath = Path.of("fixtures/fixture-api-removal"), goal = goal())

    private fun cannedPlan(): UpgradePlan =
        JacksonConfig()
            .proposalObjectMapper()
            .readValue(Path.of("eval/canned/fixture-api-removal/propose_plan.json").toFile(), UpgradePlan::class.java)

    private fun cannedDiagnosis(): BuildDiagnosis =
        JacksonConfig()
            .proposalObjectMapper()
            .readValue(Path.of("eval/canned/fixture-api-removal/diagnose.json").toFile(), BuildDiagnosis::class.java)

    private fun cannedPatch(): CodePatch =
        JacksonConfig()
            .proposalObjectMapper()
            .readValue(Path.of("eval/canned/fixture-api-removal/propose_patch.json").toFile(), CodePatch::class.java)

    private class ScriptedLlm : LlmActions() {
        override fun proposePlan(
            context: com.embabel.agent.api.common.OperationContext,
            repoModel: RepoModel,
            goal: UpgradeGoal,
            lastFailure: BuildDiagnosis?,
        ): LlmOutcome<UpgradePlan> = LlmOutcome.Accepted(ScriptedLlm.cannedPlan, emptyList())

        override fun diagnoseFailure(
            context: com.embabel.agent.api.common.OperationContext,
            build: BuildResult,
        ): LlmOutcome<BuildDiagnosis> = LlmOutcome.Accepted(ScriptedLlm.cannedDiagnosis, emptyList())

        override fun proposePatch(
            context: com.embabel.agent.api.common.OperationContext,
            diagnosis: BuildDiagnosis,
            fileContent: String,
        ): LlmOutcome<CodePatch> = LlmOutcome.Accepted(ScriptedLlm.cannedPatch, emptyList())

        companion object {
            lateinit var cannedPlan: UpgradePlan
            lateinit var cannedDiagnosis: BuildDiagnosis
            lateinit var cannedPatch: CodePatch
        }
    }

    private fun metadata() =
        com.embabel.agent.api.annotation.support
            .AgentMetadataReader()
            .createAgentMetadata(RenovatorAgent()) as com.embabel.agent.core.Agent

    private fun platform() =
        com.embabel.agent.test.integration.IntegrationTestUtils
            .dummyAgentPlatform()

    private fun resetLine() {
        Files.deleteIfExists(Path.of("var/runs/$runId/trajectory.jsonl"))
        Files.deleteIfExists(Path.of("var/runs/$runId/process.json"))
    }

    @Test
    fun `phaseKill - run is cut mid Applying and the snapshot persists the typed frame`() {
        ScriptedLlm.cannedPlan = cannedPlan()
        ScriptedLlm.cannedDiagnosis = cannedDiagnosis()
        ScriptedLlm.cannedPatch = cannedPatch()
        resetLine()
        AgentTrace.clear()
        RunAudit.clear()
        RunAudit.runId = runId
        LlmChannel.actions = ScriptedLlm()
        try {
            // The cut: maxActions=4 = analyze+analyzeRepo+propose+validate — the
            // machine is inside the Applying frame when the policy fires. Not
            // graceful: no Done, no finalize, no UpgradeComplete.
            val options =
                ProcessOptionsFactory(
                    RenovatorProperties(budget = RenovatorProperties.Budget(maxActions = 4)),
                ).processOptions(com.embabel.agent.api.common.PlannerType.GOAP)
            val running = platform().createAgentProcess(metadata(), options, mapOf("goal" to goal(), "runRequest" to runRequest))
            running.run()
            assertEquals(com.embabel.agent.core.AgentProcessStatusCode.TERMINATED, running.status, "cut mid-flight by the policy")
            val order = AgentTrace.snapshot()
            assertTrue(order.none { it == "runBuild" }, "never reached the build: $order")
            assertTrue(order.none { it == "finalizeUpgrade" }, "never finalised: $order")

            val repo = JsonFileAgentProcessRepository()
            // Persist the killed frame NOW (the JVM keeps running only to write
            // this; the demo script's process death leaves precisely this file).
            repo.update(running)
            val snapshot = repo.load(runId)
            assertNotNull(snapshot, "the snapshot must be on disk when the run dies")
            assertEquals("Applying", snapshot!!.frame, "killed in the Applying frame")
            assertEquals(1, snapshot.planSteps.size, "the plan payload is the canned migration")
            println("KILLED at stage Applying (pid ${ProcessHandle.current().pid()})")
        } finally {
            LlmChannel.actions = LlmActions()
            RunAudit.clear()
        }
    }

    @Test
    fun `phaseResume - the resumed run reaches UpgradeComplete with one Resume marker and no repeated Analyze`() {
        // The continuation needs the same scripted LLM (the migration still fails
        // the build once; the repair lane diagnoses + patches it).
        ScriptedLlm.cannedPlan = cannedPlan()
        ScriptedLlm.cannedDiagnosis = cannedDiagnosis()
        ScriptedLlm.cannedPatch = cannedPatch()
        RunAudit.clear()
        RunAudit.runId = runId
        LlmChannel.actions = ScriptedLlm()
        try {
            val service = RunService(platform(), metadata(), JsonFileAgentProcessRepository())
            val resumed = service.resume(runId)
            assertNotNull(resumed.resultOfType(UpgradeComplete::class.java), "the continuation must complete the upgrade")
            val lines = TrajectoryStore().read(runId)
            assertEquals(1, lines.count { it.contains("\"stage\":\"Analyzing\"") }, "no repeated Analyze stage:\n${lines.take(4)}")
            assertTrue(lines.any { it.contains("\"eventType\":\"Resumed\"") }, "Resumed marker present")
            assertTrue(
                lines.any { it.contains("\"eventType\":\"Completed\"") && it.contains("UpgradeComplete") },
                "completed",
            )
            assertTrue(lines.any { it.contains("\"terminal\":\"UpgradeComplete\"") })
            println("RESUMED run $runId -> UpgradeComplete")
        } finally {
            LlmChannel.actions = LlmActions()
            RunAudit.clear()
        }
    }
}
