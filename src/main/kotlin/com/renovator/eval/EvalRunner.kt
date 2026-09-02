package com.renovator.eval

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

/**
 * The eval harness (PLAN Task 6.1/6.2, D13): the four fixtures' expected-outcome
 * files ARE the dataset; this runner COMPARES a real run's trajectory against
 * each fixture's expectation (terminal state, required/forbidden stages, attempt
 * ceiling) and writes the per-run report. The run itself is driven by the caller
 * (mock mode: the scripted canned LLM; live mode: the configured provider) — the
 * runner is the deterministic judge of the comparison, never of the run.
 */
object EvalRunner {
    /** A finished run, reduced to the trajectory facts the eval asserts on. */
    data class RunResult(
        val runId: String,
        /** "UpgradeComplete" | "UpgradeBlocker" */
        val terminal: String,
        /** The StageEntered sequence in order. */
        val stages: List<String>,
        /** The number of PlanAttempted events (the attempt count). */
        val attempts: Int,
    )

    /** One fixture's verdict: pass or the named failures. */
    data class EvalOutcome(
        val fixture: String,
        val pass: Boolean,
        val failures: List<String> = emptyList(),
    )

    /** The comparison (deterministic, no model-content judgment — KL-04). */
    fun evaluate(
        expected: ExpectedOutcome,
        result: RunResult,
    ): EvalOutcome {
        val failures = mutableListOf<String>()
        if (result.terminal != expected.expectedTerminalState.name) {
            failures += "terminal state: expected ${expected.expectedTerminalState.name}, got ${result.terminal}"
        }
        val missing = expected.mustVisitStages.map { it.name } - result.stages.toSet()
        if (missing.isNotEmpty()) {
            failures += "missing required stages: $missing (had ${result.stages})"
        }
        val forbidden = result.stages.toSet() intersect expected.mustNotVisitStages.map { it.name }.toSet()
        if (forbidden.isNotEmpty()) {
            failures += "forbidden stages visited: $forbidden"
        }
        if (result.attempts > expected.maxAttempts) {
            failures += "attempts ${result.attempts} exceed ceiling ${expected.maxAttempts}"
        }
        return EvalOutcome(expected.fixture, failures.isEmpty(), failures)
    }

    /** The report (a stable, readable table — the committed artifact). */
    fun report(
        outcomes: List<EvalOutcome>,
        mode: String,
        details: Map<String, RunResult>,
    ): String =
        buildString {
            val passed = outcomes.count { it.pass }
            appendLine("# Eval report — $mode")
            appendLine()
            appendLine("- run: $mode; date: ${LocalDate.now()}; passed: $passed/${outcomes.size}")
            appendLine()
            appendLine("| fixture | verdict | attempts | terminal | failures |")
            appendLine("|---|---|---|---|---|")
            for (o in outcomes) {
                val d = details[o.fixture]
                val failures = o.failures.joinToString("; ")
                appendLine(
                    "| ${o.fixture} | ${if (o.pass) "PASS" else "FAIL"} | ${d?.attempts ?: "-"} | ${d?.terminal ?: "-"} | $failures |",
                )
            }
            appendLine()
            appendLine(
                if (outcomes.all {
                        it.pass
                    }
                ) {
                    "${outcomes.size}/${outcomes.size} fixtures as expected"
                } else {
                    "FAILED: ${outcomes.size - passed} fixture(s) did not meet expectations"
                },
            )
        }

    fun writeReport(
        content: String,
        reportsDir: Path = Path.of("eval/reports"),
        mode: String,
    ): Path {
        Files.createDirectories(reportsDir)
        val name = "${LocalDate.now()}-$mode.md"
        val path = reportsDir.resolve(name)
        Files.writeString(path, content)
        return path
    }
}
