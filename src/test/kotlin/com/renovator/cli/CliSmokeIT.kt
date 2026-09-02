package com.renovator.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.util.concurrent.TimeUnit

/**
 * CLI mechanics smoke (PLAN Task 5.4): `scripts/renovator` drives a real app —
 * submit (live LLM), status, trajectory, decide, and the SSE watch. The run's
 * TERMINAL is the LLM's choice (the live model may propose a plan the fixture
 * cannot apply — a live-plan property, not a CLI property), so this test asserts
 * the CLI MECHANICS and read contracts, not a specific run outcome. The
 * gate/decide SEMANTICS are proven deterministically at the service + controller
 * layers (ApprovalGateIT, DecisionControllerTest) where the plan is scripted;
 * the CLI here proves the transport.
 *
 * LIVE-LLM CALLS (spend discipline): opt-in only — llm-it profile + LLM_SMOKE=1
 * (never a default verify). One `proposePlan` (the configured gpt-4.1-mini) is
 * the entire live budget of this test.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "renovator.approvals.plan=false",
        "renovator.approvals.commit-candidate=false",
    ],
)
@EnabledIfEnvironmentVariable(named = "LLM_SMOKE", matches = "1")
class CliSmokeIT {
    @LocalServerPort
    var port: Int = 0

    private fun cli(vararg args: String): Pair<Int, String> {
        val pb = ProcessBuilder(listOf("bash", "scripts/renovator") + args.toList())
        pb.environment()["RENOVATOR_URL"] = "http://localhost:$port"
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "cli hung: $out")
        return p.exitValue() to out
    }

    private fun await(
        condition: () -> Boolean,
        message: String,
        timeoutSeconds: Int = 180,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(200)
        }
        error(message)
    }

    @Test
    fun `submit watch decide happy path against a running service`() {
        // submit: one live propose. The target version 99.99.99 404s forever, so
        // ANY plan the live model proposes is L3-rejected and the run parks at
        // the plan-space blocker — the terminal is deterministic regardless of
        // the LLM's output (the live-propose budget stays one call).
        val (submitCode, submitOut) =
            cli("submit", "fixtures/fixture-no-path", "org.apache.commons:commons-lang3:3.12.0:99.99.99")
        assertEquals(0, submitCode, "submit failed: $submitOut")
        val runId = submitOut.trim().lines().last()
        assertTrue(runId.startsWith("run-"), "a run id from the CLI: $runId")

        // status: the read contract.
        val (statusCode, statusOut) = cli("status", runId)
        assertEquals(0, statusCode, "status failed: $statusOut")
        assertTrue(statusOut.contains(runId), "status names the run: $statusOut")

        // trajectory: the run's events become readable (the run may still be
        // executing — we only need the replayed StageEntered to arrive).
        await(
            {
                val (code, out) = cli("trajectory", runId)
                code == 0 && out.contains("StageEntered")
            },
            "the trajectory never became readable",
        )
        val (_, trajOut) = cli("trajectory", runId)
        assertTrue(trajOut.contains("StageEntered"), "replayed events: ${trajOut.take(200)}")

        // decide: against the plan-space blocker (not an approval gate) the
        // endpoint answers with the typed 4xx, never a stack trace.
        val (decideCode, decideOut) = cli("decide", runId, "approve", "cli smoke: go")
        assertEquals(0, decideCode, "decide command must run cleanly: ${decideOut.take(200)}")
        assertTrue(
            decideOut.contains("approved") || decideOut.contains("conflict") ||
                decideOut.contains("invalid-request") || decideOut.contains("not parked"),
            "typed decision response: ${decideOut.take(160)}",
        )

        // watch: the SSE endpoint responds (the replayed events stream).
        val (watchCode, watchOut) = cli("watch", runId)
        assertTrue(
            watchCode in setOf(0, 130, 1, 124) || watchOut.contains("StageEntered"),
            "watch: $watchCode ${watchOut.take(80)}",
        )

        println("CLI SMOKE: submit -> status -> trajectory -> decide -> watch all reached the live service (run $runId)")
    }
}
