package com.renovator.agent

import com.renovator.agent.actions.LlmActions
import com.renovator.agent.actions.LlmOutcome
import com.renovator.agent.llm.LlmChannel
import com.renovator.audit.AgentTrace
import com.renovator.audit.RunAudit
import com.renovator.audit.TrajectoryStore
import com.renovator.config.JacksonConfig
import com.renovator.domain.BuildDiagnosis
import com.renovator.domain.BuildResult
import com.renovator.domain.ChangeScope
import com.renovator.domain.CodePatch
import com.renovator.domain.DependencyTarget
import com.renovator.domain.PlanStep
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeComplete
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import com.renovator.domain.VersionChange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Repair loop (PLAN Task 4.2, D13) on fixture-api-removal: a coordinate migration
 * (commons-lang:commons-lang 2.6 -> org.apache.commons:commons-lang3:3.14.0) that
 * breaks compilation at the removed `escapeSql` API. With the scripted LLM the
 * full loop must run: propose -> validate -> apply -> build FAILS ->
 * diagnoseFailure -> proposePatch -> validatePatch (L1-L2) -> apply -> build green
 * -> finalize. Exactly ONE repair cycle, and the typed trajectory must show
 * Verifying -> Repairing -> Applying with a ValidationOutcome for the patch
 * BEFORE the second BuildObserved (the patch was validated before it touched the
 * executor).
 */
class RepairLoopIT {
    private fun goal(): UpgradeGoal =
        UpgradeGoal(targets = listOf(DependencyTarget("org.apache.commons", "commons-lang3", "2.6", "3.14.0")))

    private val runRequest = RunRequest(repoPath = Path.of("fixtures/fixture-api-removal"), goal = goal())
    private val cannedPlan =
        UpgradePlan(
            steps =
                listOf(
                    PlanStep.VersionStep(
                        VersionChange("org.apache.commons", "commons-lang3", "2.6", "3.14.0", ChangeScope.DIRECT),
                    ),
                ),
            rationale = "migrate commons-lang 2.6 to commons-lang3 3.14.0",
        )

    private fun cannedDiagnosis(): BuildDiagnosis =
        JacksonConfig()
            .proposalObjectMapper()
            .readValue(
                Path.of("eval/canned/fixture-api-removal/diagnose.json").toFile(),
                BuildDiagnosis::class.java,
            )

    private fun cannedPatch(): CodePatch =
        JacksonConfig()
            .proposalObjectMapper()
            .readValue(
                Path.of("eval/canned/fixture-api-removal/propose_patch.json").toFile(),
                CodePatch::class.java,
            )

    private class ScriptedLlm : LlmActions() {
        private val planQueue = ArrayDeque<LlmOutcome<UpgradePlan>>()
        private val diagnosisQueue = ArrayDeque<LlmOutcome<BuildDiagnosis>>()
        private val patchQueue = ArrayDeque<LlmOutcome<CodePatch>>()
        var lastFailedBuild: BuildResult? = null
        var lastPatchContext: String? = null

        fun enqueuePlan(outcome: LlmOutcome<UpgradePlan>) {
            planQueue.addLast(outcome)
        }

        fun enqueueDiagnosis(outcome: LlmOutcome<BuildDiagnosis>) {
            diagnosisQueue.addLast(outcome)
        }

        fun enqueuePatch(outcome: LlmOutcome<CodePatch>) {
            patchQueue.addLast(outcome)
        }

        override fun proposePlan(
            context: com.embabel.agent.api.common.OperationContext,
            repoModel: com.renovator.domain.RepoModel,
            goal: UpgradeGoal,
            lastFailure: com.renovator.domain.BuildDiagnosis?,
        ): LlmOutcome<UpgradePlan> = planQueue.removeFirst()

        override fun diagnoseFailure(
            context: com.embabel.agent.api.common.OperationContext,
            build: BuildResult,
        ): LlmOutcome<BuildDiagnosis> {
            lastFailedBuild = build
            return diagnosisQueue.removeFirst()
        }

        override fun proposePatch(
            context: com.embabel.agent.api.common.OperationContext,
            diagnosis: BuildDiagnosis,
            fileContent: String,
        ): LlmOutcome<CodePatch> {
            lastPatchContext = fileContent
            return patchQueue.removeFirst()
        }
    }

    private fun runUpgrade(scripted: ScriptedLlm): List<String> {
        AgentTrace.clear()
        RunAudit.clear()
        RunAudit.runId = "repair-it"
        // A stale trajectory from an earlier run must not skew the sequence/count
        // assertions (each run appends to the same run id).
        Files.deleteIfExists(Path.of("var/runs/repair-it/trajectory.jsonl"))
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
            RunAudit.clear()
        }
    }

    @Test
    fun `agent diagnoses the escapeSql removal, patches, and reaches green with one repair cycle`() {
        val scripted = ScriptedLlm()
        scripted.enqueuePlan(LlmOutcome.Accepted(cannedPlan, emptyList()))
        scripted.enqueueDiagnosis(LlmOutcome.Accepted(cannedDiagnosis(), emptyList()))
        scripted.enqueuePatch(LlmOutcome.Accepted(cannedPatch(), emptyList()))
        val order = runUpgrade(scripted)

        // Exactly one repair cycle around the loop. The LANE is asserted, not just
        // the counts: the PATCH_CODE-only diagnosis closes the replan lane
        // (DiagnosisHintCondition), so the machine must repair through the patch
        // lane — a planner drift back to the replan lane (the 4.4 incident;
        // postmortem in the phase-4 report) fails here on the `replan` count.
        assertEquals(1, order.count { it == "diagnoseFailure" }, "one diagnosis: $order")
        assertEquals(1, order.count { it == "proposePatch" }, "one patch proposal: $order")
        assertEquals(1, order.count { it == "validatePatch" }, "one patch validation: $order")
        assertEquals(0, order.count { it == "replan" }, "patch lane chosen, not the replan lane: $order")
        assertEquals(2, order.count { it == "runBuild" }, "fail then green: exactly two builds: $order")
        assertEquals(2, order.count { it == "applyValidatedChanges" }, "initial apply + repair apply: $order")
        assertTrue(order.last() == "finalizeUpgrade", "run completes: $order")

        // Repairing is entered AFTER the first (failed) build, and its actions
        // precede the second apply.
        val firstBuild = order.indexOfFirst { it == "runBuild" }
        val repairIdx = order.indexOfFirst { it == "diagnoseFailure" }
        val secondApply = order.indexOfLast { it == "applyValidatedChanges" }
        assertTrue(firstBuild in 0 until repairIdx, "build 1 before diagnosis: $order")
        assertTrue(repairIdx < secondApply, "repair before the final apply: $order")
        assertTrue(order.indexOfLast { it == "runBuild" } > repairIdx, "build 2 after the repair: $order")
        assertTrue(secondApply < order.indexOfLast { it == "runBuild" }, "apply before build 2: $order")

        // The failing build really named the removed type (phase-1 drift disclosure:
        // javac names StringEscapeUtils, the removed TYPE — not the method escapeSql,
        // which appears in the diagnosis/patch instead; see fixture README.md).
        val failedLog = scripted.lastFailedBuild?.log?.head + "\n" + (scripted.lastFailedBuild?.log?.tail ?: "")
        assertTrue(failedLog.contains("StringEscapeUtils"), "failed log names the removed type:\n$failedLog")
        assertTrue(
            failedLog.contains("EscapeSqlFormatter") || failedLog.contains("escapeSql"),
            "failed log names the failing file:\n$failedLog",
        )

        // The patch context handed to the LLM is the SNAPSHOT content (D7): the
        // migrated workspace copy, never the source tree.
        val patchContext = scripted.lastPatchContext.orEmpty()
        assertTrue(
            patchContext.contains("import org.apache.commons.lang.StringEscapeUtils;"),
            "patch context is the pre-patch source:\n$patchContext",
        )
    }

    @Test
    fun `trajectory shows Verifying then Repairing then Applying with patch validated before the second build`() {
        val scripted = ScriptedLlm()
        scripted.enqueuePlan(LlmOutcome.Accepted(cannedPlan, emptyList()))
        scripted.enqueueDiagnosis(LlmOutcome.Accepted(cannedDiagnosis(), emptyList()))
        scripted.enqueuePatch(LlmOutcome.Accepted(cannedPatch(), emptyList()))
        runUpgrade(scripted)
        val lines = TrajectoryStore().read("repair-it")

        fun indexes(marker: String): List<Int> = lines.withIndex().filter { it.value.contains(marker) }.map { it.index }

        val stage = indexes("\"eventType\":\"StageEntered\"")
        val stages = stage.map { Regex(""""stage"\s*:\s*"([^"]+)"""").find(lines[it])!!.groupValues[1] }
        assertEquals(
            listOf("Analyzing", "Planning", "Applying", "Verifying", "Repairing", "Applying", "Verifying", "Done"),
            stages,
            "verbatim transition sequence:\n${stage.joinToString("\n") { lines[it] }}",
        )

        // Exactly one repair cycle: a single Repairing entry, and the patch was
        // validated (accepted ValidationOutcome) BEFORE the second build ran.
        assertEquals(1, stages.count { it == "Repairing" }, "one repair cycle")

        val builds = indexes("\"eventType\":\"BuildObserved\"")
        assertEquals(2, builds.size, "two builds observed")
        assertTrue(lines[builds[0]].contains("\"success\":false"), "first build fails: ${lines[builds[0]]}")
        assertTrue(lines[builds[1]].contains("\"success\":true"), "second build green: ${lines[builds[1]]}")

        val patchValidation = indexes("\"eventType\":\"ValidationOutcome\"").first { lines[it].contains("\"accepted\":true") }
        assertTrue(patchValidation < builds[1], "patch validated before the second build")
        assertTrue(lines[patchValidation].contains("L1") && lines[patchValidation].contains("L2"), "patch proof names L1,L2")

        // Canned LLM outputs carry the acceptance signal (PLAN demo:
        // grep -c escapeSql var/runs/*/trajectory.jsonl >= 2: diagnosis root
        // cause + patch justification).
        val escapeSqlCount = lines.count { it.contains("escapeSql") }
        assertTrue(escapeSqlCount >= 2, "escapeSql in diagnosis and patch: lines=$escapeSqlCount")
        assertTrue(idxOf(lines, "ProposalReceived", "BuildDiagnosis") >= 0, "diagnosis proposal recorded")
        assertTrue(idxOf(lines, "ProposalReceived", "CodePatch") >= 0, "patch proposal recorded")
    }

    private fun idxOf(
        lines: List<String>,
        eventType: String,
        kind: String,
    ): Int = lines.indexOfFirst { it.contains("\"eventType\":\"$eventType\"") && it.contains("\"kind\":\"$kind\"") }
}
