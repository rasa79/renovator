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
import com.renovator.domain.RepoModel
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeComplete
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Two-hop replanning (PLAN Task 4.3, §6.1 trace) on fixture-transitive-conflict:
 * the direct bump fails enforcer dependencyConvergence (guice 7.0.0 transitively
 * pins guava 31.0.1-jre — the DRIFT coordinates per the phase-1 environment note;
 * PLAN §6.1's 32.1.2-jre was corrected), the diagnosis carries PIN_TRANSITIVE +
 * MULTI_HOP (so the patch lane is closed and only the replan lane is plannable),
 * and the second proposal is the two-hop plan: management-scope pin first, then
 * the direct bump. The real sandbox build proves the pin resolves convergence.
 */
class TwoHopReplanIT {
    private fun goal(): UpgradeGoal =
        UpgradeGoal(targets = listOf(DependencyTarget("com.google.guava", "guava", "31.0.1-jre", "33.4.8-jre")))

    private val runRequest = RunRequest(repoPath = Path.of("fixtures/fixture-transitive-conflict"), goal = goal())

    private fun canned(name: String): UpgradePlan =
        JacksonConfig()
            .proposalObjectMapper()
            .readValue(Path.of("eval/canned/fixture-transitive-conflict/$name.json").toFile(), UpgradePlan::class.java)

    private fun cannedDiagnosis(): BuildDiagnosis =
        JacksonConfig()
            .proposalObjectMapper()
            .readValue(
                Path.of("eval/canned/fixture-transitive-conflict/diagnose.json").toFile(),
                BuildDiagnosis::class.java,
            )

    private class ScriptedLlm : LlmActions() {
        private val planQueue = ArrayDeque<LlmOutcome<UpgradePlan>>()
        var contextPerProposal = mutableListOf<BuildDiagnosis?>()
        var lastFailedBuild: BuildResult? = null
        var diagnoseRan = false

        fun enqueuePlan(outcome: LlmOutcome<UpgradePlan>) {
            planQueue.addLast(outcome)
        }

        override fun proposePlan(
            context: com.embabel.agent.api.common.OperationContext,
            repoModel: RepoModel,
            goal: UpgradeGoal,
            lastFailure: BuildDiagnosis?,
        ): LlmOutcome<UpgradePlan> {
            contextPerProposal += lastFailure
            return planQueue.removeFirst()
        }

        override fun diagnoseFailure(
            context: com.embabel.agent.api.common.OperationContext,
            build: BuildResult,
        ): LlmOutcome<BuildDiagnosis> {
            lastFailedBuild = build
            diagnoseRan = true
            return LlmOutcome.Accepted(cannedDiagnosis, emptyList())
        }

        override fun proposePatch(
            context: com.embabel.agent.api.common.OperationContext,
            diagnosis: BuildDiagnosis,
            fileContent: String,
        ): LlmOutcome<CodePatch> = error("patch lane must be closed: the diagnosis has no PATCH_CODE hint")

        companion object {
            lateinit var cannedDiagnosis: BuildDiagnosis
        }
    }

    private fun runUpgrade(scripted: ScriptedLlm): List<String> {
        AgentTrace.clear()
        RunAudit.clear()
        RunAudit.runId = "replan-it"
        Files.deleteIfExists(Path.of("var/runs/replan-it/trajectory.jsonl"))
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
    fun `direct bump fails enforcer convergence naming guava, then the two-hop replan reaches green`() {
        val direct = canned("propose_plan_direct")
        val replan = canned("propose_plan_replan")
        // Canned bytes carry the drift coordinates (verified against the fixture
        // and the guice-parent pom): the PLAN's assumed 32.1.2-jre pin does not exist.
        val directChange = (direct.steps[0] as PlanStep.VersionStep).change
        assertEquals("31.0.1-jre", directChange.fromVersion, "drift coordinate in the canned direct plan")
        assertEquals("33.4.8-jre", directChange.toVersion, "drift coordinate in the canned direct plan")
        val replanFirst = (replan.steps[0] as PlanStep.VersionStep).change
        assertEquals(ChangeScope.MANAGEMENT, replanFirst.scope, "hop 1: management pin")
        assertEquals(ChangeScope.DIRECT, (replan.steps[1] as PlanStep.VersionStep).change.scope, "hop 2: direct bump")
        assertEquals("31.0.1-jre", replanFirst.fromVersion, "hop 1 carries the drift from-version")

        val scripted =
            ScriptedLlm().apply {
                ScriptedLlm.cannedDiagnosis = cannedDiagnosis()
            }
        scripted.enqueuePlan(LlmOutcome.Accepted(direct, emptyList()))
        scripted.enqueuePlan(LlmOutcome.Accepted(replan, emptyList()))
        val order = runUpgrade(scripted)

        // The §6.1 shape: two proposals, one diagnosis, one replan transition, a
        // failing build then a green one — and NO patch lane activity.
        assertEquals(2, order.count { it == "proposeUpgradePlan" }, "attempt 1 + attempt 2: $order")
        assertEquals(1, order.count { it == "diagnoseFailure" }, "one diagnosis: $order")
        assertEquals(1, order.count { it == "replan" }, "one replan transition: $order")
        assertEquals(0, order.count { it == "proposePatch" }, "patch lane closed: $order")
        assertEquals(0, order.count { it == "validatePatch" }, "patch lane closed: $order")
        assertEquals(2, order.count { it == "runBuild" }, "fail then green: $order")
        assertTrue(order.last() == "finalizeUpgrade", "$order")

        // The failed build really names the enforcer convergence conflict with the
        // drift coordinates (guava 33.4.8-jre direct vs 31.0.1-jre via guice).
        val failedLog = scripted.lastFailedBuild?.log?.head + "\n" + (scripted.lastFailedBuild?.log?.tail ?: "")
        assertTrue(failedLog.contains("DependencyConvergence"), "enforcer rule named:\n$failedLog")
        assertTrue(failedLog.contains("guava"), "guava named:\n$failedLog")
        assertTrue(failedLog.contains("33.4.8-jre") && failedLog.contains("31.0.1-jre"), "both versions named:\n$failedLog")

        // The SECOND proposal received the failure diagnosis (the replan context);
        // the first did not.
        assertEquals(2, scripted.contextPerProposal.size, "two proposals observed")
        assertEquals(null, scripted.contextPerProposal[0], "first proposal has no failure context")
        assertNotNull(scripted.contextPerProposal[1], "second proposal carries the diagnosis")
        assertTrue(
            scripted.contextPerProposal[1]!!.suggestedActions.any { it.kind == com.renovator.domain.HintKind.PIN_TRANSITIVE },
            "diagnosis rode into the replan prompt",
        )
    }

    @Test
    fun `trajectory matches the section 6'1 sequence with two plan attempts`() {
        val scripted =
            ScriptedLlm().apply {
                ScriptedLlm.cannedDiagnosis = cannedDiagnosis()
            }
        scripted.enqueuePlan(LlmOutcome.Accepted(canned("propose_plan_direct"), emptyList()))
        scripted.enqueuePlan(LlmOutcome.Accepted(canned("propose_plan_replan"), emptyList()))
        runUpgrade(scripted)
        val lines = TrajectoryStore().read("replan-it")

        fun indexes(marker: String): List<Int> = lines.withIndex().filter { it.value.contains(marker) }.map { it.index }

        val stage = indexes("\"eventType\":\"StageEntered\"")
        val stages = stage.map { Regex(""""stage"\s*:\s*"([^"]+)"""").find(lines[it])!!.groupValues[1] }
        assertEquals(
            listOf("Analyzing", "Planning", "Applying", "Verifying", "Repairing", "Planning", "Applying", "Verifying", "Done"),
            stages,
            "verbatim transition sequence:\n${stage.joinToString("\n") { lines[it] }}",
        )

        // Two plan attempts: the direct one and the two-hop one (distinct rationales).
        val attempts = indexes("\"eventType\":\"PlanAttempted\"")
        assertEquals(2, attempts.size, "two PlanAttempted events")
        assertTrue(lines[attempts[0]].contains("single direct bump"), lines[attempts[0]])
        assertTrue(lines[attempts[1]].contains("pin the transitive guava"), lines[attempts[1]])
        assertTrue(lines[attempts[1]].contains("\"stepCount\":2"), "two-hop plan has two steps: ${lines[attempts[1]]}")

        // Builds: first fails with the enforcer goal, second is green.
        val builds = indexes("\"eventType\":\"BuildObserved\"")
        assertEquals(2, builds.size, "two builds observed")
        assertTrue(lines[builds[0]].contains("\"success\":false"), lines[builds[0]])
        assertTrue(lines[builds[0]].contains("enforce"), "enforcer goal in the failure: ${lines[builds[0]]}")
        assertTrue(lines[builds[1]].contains("\"success\":true"), lines[builds[1]])

        // The diagnosis and the completed run are recorded.
        assertTrue(lines.any { it.contains("\"eventType\":\"ProposalReceived\"") && it.contains("BuildDiagnosis") })
        assertTrue(lines.any { it.contains("\"eventType\":\"Completed\"") && it.contains("UpgradeComplete") })
        assertFalse(
            lines.any { it.contains("\"eventType\":\"ProposalReceived\"") && it.contains("CodePatch") },
            "no patch lane in the trajectory",
        )
    }
}
