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
 * phaseKill: starts the upgrade asynchronously and persists the typed snapshot
 * the moment the machine enters the Applying frame. In DEMO mode
 * (`-DkillDemo=1`, set by scripts/demo-kill-resume.sh) the test then HOLDS the
 * JVM open — the script kills it with `kill -9` while this JVM is genuinely
 * mid-flight (the run thread is executing its sandbox build when the SIGKILL
 * lands), and the persisted snapshot plus trajectory are the only survivors of
 * that JVM session. Without the property (the automated gate) the run simply
 * completes and the persist is asserted.
 *
 * phaseResume: a fresh JVM re-seeds from the snapshot (RunService.resume — the
 * continuation re-enters at the last apply, KL-08) and runs to UpgradeComplete.
 */
@TestMethodOrder(MethodOrderer.MethodName::class)
class KillResumeIT {
    private val runId = "kill-demo"
    private val killDemo: Boolean = System.getProperty("killDemo") == "1"

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
        Files.deleteIfExists(Path.of("var/runs/$runId/ready-to-kill.txt"))
        Files.deleteIfExists(Path.of("var/runs/$runId/pid.txt"))
    }

    private fun awaitStage(
        stage: String,
        timeoutSeconds: Int = 120,
    ) {
        val traj = Path.of("var/runs/$runId/trajectory.jsonl")
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(timeoutSeconds.toLong())
        while (System.nanoTime() < deadline) {
            if (Files.exists(traj) && Files.readString(traj).contains("\"stage\":\"$stage\"")) {
                return
            }
            Thread.sleep(50)
        }
        error("timed out waiting for stage $stage; trajectory:\n${if (Files.exists(traj)) Files.readString(traj) else "(none)"}")
    }

    @Test
    fun `phaseKill - persist the Applying frame, then (demo mode) hold open until the JVM is killed`() {
        ScriptedLlm.cannedPlan = cannedPlan()
        ScriptedLlm.cannedDiagnosis = cannedDiagnosis()
        ScriptedLlm.cannedPatch = cannedPatch()
        resetLine()
        AgentTrace.clear()
        RunAudit.clear()
        RunAudit.runId = runId
        LlmChannel.actions = ScriptedLlm()
        try {
            // The run is STARTED, not cut: with the demo property the script's
            // kill -9 lands while this JVM is genuinely mid-flight.
            val options =
                ProcessOptionsFactory()
                    .processOptions(com.embabel.agent.api.common.PlannerType.GOAP)
            val running = platform().createAgentProcess(metadata(), options, mapOf("goal" to goal(), "runRequest" to runRequest))
            val thread = Thread { running.run() }
            thread.start()

            awaitStage("Applying")
            val repo = JsonFileAgentProcessRepository()
            repo.update(running) // persist the current frame + applied payload
            val snapshot = repo.load(runId)
            assertNotNull(snapshot, "the snapshot must be on disk at the Applying marker")
            assertTrue(snapshot!!.planSteps.isNotEmpty(), "the applied payload is in the snapshot")

            val pid = ProcessHandle.current().pid()
            Files.writeString(Path.of("var/runs/$runId/pid.txt"), pid.toString())
            Files.writeString(
                Path.of("var/runs/$runId/ready-to-kill.txt"),
                "frame=${snapshot.frame}\nsnapshotAt=${snapshot.snapshotAt}\nreadyAt=${java.time.Instant.now()}\n",
            )
            println("APPLYING FRAME PERSISTED (frame=${snapshot.frame}, snapshotAt=${snapshot.snapshotAt}) — pid $pid")

            if (killDemo) {
                // Hold open: the script polls ready-to-kill.txt then SIGKILLs this
                // pid. The run thread is mid-flight (building in the sandbox).
                while (true) {
                    Thread.sleep(60_000)
                }
            } else {
                // Automated gate: the run completes; the persist is the assertion.
                thread.join(300_000)
                assertTrue(!thread.isAlive, "the run finished")
                val lines = TrajectoryStore().read(runId)
                assertTrue(lines.any { it.contains("\"stage\":\"Applying\"") }, "Applying was entered")
            }
        } finally {
            LlmChannel.actions = LlmActions()
            RunAudit.clear()
        }
    }

    @Test
    fun `phaseResume - the resumed run reaches UpgradeComplete with one Resume marker and no repeated Analyze`() {
        // The continuation needs the same scripted LLM (the migration still fails
        // the build once; the repair lane diagnoses + patches it — re-derived in
        // the continuation, KL-08).
        ScriptedLlm.cannedPlan = cannedPlan()
        ScriptedLlm.cannedDiagnosis = cannedDiagnosis()
        ScriptedLlm.cannedPatch = cannedPatch()
        RunAudit.clear()
        RunAudit.runId = runId
        LlmChannel.actions = ScriptedLlm()
        try {
            val service = RunService(platform(), explicitAgent = metadata(), repository = JsonFileAgentProcessRepository())
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
