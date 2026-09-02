package com.renovator.api

import com.renovator.agent.actions.LlmActions
import com.renovator.agent.actions.LlmOutcome
import com.renovator.agent.llm.LlmChannel
import com.renovator.config.JacksonConfig
import com.renovator.domain.BuildDiagnosis
import com.renovator.domain.BuildResult
import com.renovator.domain.CodePatch
import com.renovator.domain.DependencyTarget
import com.renovator.domain.RepoModel
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * RunService (PLAN Task 5.1, docker-it): the REAL async service — submit returns
 * immediately, the run transitions to Done with the final stage exposed, and the
 * single-run gate rejects a second concurrent submission (KL-01, the 409 path
 * the control API promises — proven at the service layer here, at the HTTP layer
 * in RunControllerTest).
 */
class RunServiceIT {
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
        ): LlmOutcome<BuildDiagnosis> = error("n/a")

        override fun proposePatch(
            context: com.embabel.agent.api.common.OperationContext,
            diagnosis: BuildDiagnosis,
            fileContent: String,
        ): LlmOutcome<CodePatch> = error("n/a")

        companion object {
            lateinit var canned: UpgradePlan
        }
    }

    private fun await(
        condition: () -> Boolean,
        message: String,
        timeoutSeconds: Int = 60,
    ) {
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(timeoutSeconds.toLong())
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(100)
        }
        error(message)
    }

    private fun service(): RunService {
        val meta =
            com.embabel.agent.api.annotation.support.AgentMetadataReader().createAgentMetadata(
                com.renovator.agent.RenovatorAgent(),
            ) as com.embabel.agent.core.Agent
        val ap =
            com.embabel.agent.test.integration.IntegrationTestUtils
                .dummyAgentPlatform()
        return RunService(ap, explicitAgent = meta, repository = com.renovator.persistence.JsonFileAgentProcessRepository())
    }

    @Test
    fun `an async submit transitions the run to Done and exposes the final stage`() {
        ScriptedLlm.canned = cannedPlan()
        LlmChannel.actions = ScriptedLlm()
        val svc = service()
        try {
            val runId = svc.submit(goal(), RunRequest(Path.of("fixtures/fixture-clean"), goal()))
            assertTrue(runId.startsWith("run-"), "a typed run id: $runId")

            val deadline =
                System.nanoTime() +
                    java.util.concurrent.TimeUnit.SECONDS
                        .toNanos(120)
            var status = svc.status(runId)
            while (System.nanoTime() < deadline && status.stage != "Done") {
                Thread.sleep(100)
                status = svc.status(runId)
            }
            assertEquals("Done", status.stage, "final stage exposed: $status")
            assertEquals(1, status.attempts, "one plan attempt recorded")
            // The typed trajectory is readable through the service; the terminal
            // event lands just after the Done state (finalize runs next).
            await(
                { svc.trajectory(runId).any { it.contains("\"terminal\":\"UpgradeComplete\"") } },
                "the terminal event never appeared",
            )
        } finally {
            LlmChannel.actions = LlmActions()
            svc.close()
        }
    }

    @Test
    fun `a second concurrent submission is rejected (KL-01 409 path)`() {
        ScriptedLlm.canned = cannedPlan()
        LlmChannel.actions = ScriptedLlm()
        val svc = service()
        try {
            val first = svc.submit(goal(), RunRequest(Path.of("fixtures/fixture-clean"), goal()))
            // The first run is still active (async): the gate must refuse.
            val thrown =
                assertThrows(ConflictException::class.java) {
                    svc.submit(goal(), RunRequest(Path.of("fixtures/fixture-clean"), goal()))
                }
            assertTrue(thrown.message!!.contains("single-run enforcement"), "verbatim KL-01 reason: ${thrown.message}")
            // Once the first run drains, a new submission is accepted again. The
            // run's Done frame is visible BEFORE the executor thread finishes
            // finalize + repository.update + registry.finish, so poll the GATE
            // (a submit that succeeds) rather than the stage — polling the stage
            // races the finally() and can still see the slot active.
            val deadline =
                System.nanoTime() +
                    java.util.concurrent.TimeUnit.SECONDS
                        .toNanos(120)
            var second: String? = null
            while (System.nanoTime() < deadline && second == null) {
                if (svc.status(first).stage == "Done") {
                    try {
                        second = svc.submit(goal(), RunRequest(Path.of("fixtures/fixture-clean"), goal()))
                    } catch (_: ConflictException) {
                        // still draining; retry
                    }
                }
                if (second == null) {
                    Thread.sleep(100)
                }
            }
            assertNotNull(second, "the slot frees after completion (stage never reached Done)")
            assertTrue(second!!.startsWith("run-"), "the slot frees after completion: $second")
        } finally {
            LlmChannel.actions = LlmActions()
            svc.close()
        }
    }
}
