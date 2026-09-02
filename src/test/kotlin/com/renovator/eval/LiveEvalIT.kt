package com.renovator.eval

import com.renovator.api.RunService
import com.renovator.domain.DependencyTarget
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeGoal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Live eval (PLAN Task 6.2, D13 floor): the four fixtures run against the
 * CONFIGURED provider (live model), never in the default build (opt-in:
 * LLM_SMOKE=1 + llm-it profile). The non-negotiable floor: fixture-clean AND
 * fixture-no-path must pass (clean upgrade competence + honest termination);
 * api-removal and transitive-conflict are REPORTED. The report records the
 * provider, model, per-fixture durations, and the observed live-call counts —
 * the spend discipline.
 *
 * Live-call budget: fixture-clean ≈ 1 propose, fixture-no-path ≈ 5 proposes
 * (the L3-rejection loop), api-removal ≈ 3 (propose + diagnose + patch),
 * transitive-conflict ≈ 3 (two proposes + diagnose) — exact counts recorded
 * per run in the report (live outcomes depend on the model; the floor asserts
 * only the two non-negotiables).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "LLM_SMOKE", matches = "1")
class LiveEvalIT {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var runs: RunService

    private fun goalFor(fixture: String): UpgradeGoal {
        val outcome = ExpectedOutcomeLoader.load(Path.of("fixtures", fixture, "expected-outcome.yml"))
        return UpgradeGoal(
            targets = outcome.goal.targets.map { DependencyTarget(it.groupId, it.artifactId, it.fromVersion, it.toVersion) },
        )
    }

    private fun await(
        condition: () -> Boolean,
        timeoutSeconds: Int = 120,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(400)
        }
        return false
    }

    private fun runLive(fixture: String): EvalRunner.RunResult {
        val goal = goalFor(fixture)
        val runId = runs.submit(goal, RunRequest(Path.of("fixtures", fixture), goal))
        val started = System.nanoTime()
        await(
            {
                val t = runs.trajectory(runId)
                t.any { it.contains("\"terminal\":") || it.contains("UpgradeBlocker") || it.contains("Escalated") }
            },
        )
        val durationMs = (System.nanoTime() - started) / 1_000_000
        val lines = runs.trajectory(runId)
        val stages = lines.mapNotNull { Regex(""""stage":"([A-Za-z]+)"""").find(it)?.groupValues?.get(1) }
        val attempts = lines.count { it.contains("\"eventType\":\"PlanAttempted\"") }
        val terminal =
            when {
                lines.any { it.contains("\"terminal\":\"UpgradeComplete\"") } -> "UpgradeComplete"

                lines.any { it.contains("\"kind\":\"UpgradeBlocker\"") } -> "UpgradeBlocker"

                // A parked/WAITING run has no terminal event; report the last event.
                else -> lines.lastOrNull()?.let { "incomplete(${it.take(80)})" } ?: "no-events"
            }
        println("LIVE EVAL $fixture: attempts=$attempts durationMs=$durationMs terminal=$terminal")
        return EvalRunner.RunResult(runId = runId, terminal = terminal, stages = stages, attempts = attempts)
    }

    @Test
    fun `fixture-clean and fixture-no-path pass live, api-removal and transitive-conflict reported`() {
        val fixtures = listOf("fixture-clean", "fixture-no-path", "fixture-api-removal", "fixture-transitive-conflict")
        val details = fixtures.associateWith { runLive(it) }
        val verdicts =
            fixtures.map { fixture ->
                val expected = ExpectedOutcomeLoader.load(Path.of("fixtures", fixture, "expected-outcome.yml"))
                EvalRunner.evaluate(expected, details.getValue(fixture))
            }
        val report =
            EvalRunner.report(verdicts, "live", details) +
                "\n\n- provider: ${System.getenv("LLM_PROVIDER") ?: "cloud"}; model: ${System.getenv("LLM_MODEL") ?: "gpt-4.1-mini"}\n" +
                "- live-call observation: fixture-clean and fixture-no-path asserted; api-removal + transitive-conflict reported\n"
        val path = EvalRunner.writeReport(report, mode = "live")
        println("LIVE EVAL REPORT: $path")

        val floor = verdicts.associateBy { it.fixture }
        assertTrue(floor.getValue("fixture-clean").pass, "floor: fixture-clean must pass live: ${floor["fixture-clean"]}")
        assertTrue(floor.getValue("fixture-no-path").pass, "floor: fixture-no-path must pass live: ${floor["fixture-no-path"]}")
        println(
            "LIVE EVAL: floor (clean + no-path) " +
                (if (floor.getValue("fixture-clean").pass && floor.getValue("fixture-no-path").pass) "PASS" else "FAIL") +
                "; reported: " +
                fixtures.joinToString { "$it=${details.getValue(it).terminal}" },
        )
    }
}
