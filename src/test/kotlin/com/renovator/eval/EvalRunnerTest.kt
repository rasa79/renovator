package com.renovator.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The runner's comparison logic (PLAN Task 6.1): a mismatching terminal state
 * fails the fixture, exceeding the attempt ceiling fails, and a fully matching
 * run passes — the deterministic judge, never model-content (KL-04).
 */
class EvalRunnerTest {
    private val expected = ExpectedOutcomeLoader.load(Path.of("fixtures/fixture-clean/expected-outcome.yml"))

    private fun result(
        terminal: String = "UpgradeComplete",
        stages: List<String> = listOf("Analyzing", "Planning", "Applying", "Verifying", "Done"),
        attempts: Int = 1,
    ) = EvalRunner.RunResult(runId = "r1", terminal = terminal, stages = stages, attempts = attempts)

    @Test
    fun `a fully matching run passes`() {
        val outcome = EvalRunner.evaluate(expected, result())
        assertTrue(outcome.pass, "expected pass: $outcome")
    }

    @Test
    fun `mismatched terminal state fails the fixture`() {
        val outcome = EvalRunner.evaluate(expected, result(terminal = "UpgradeBlocker"))
        assertFalse(outcome.pass, "expected fail: $outcome")
        assertTrue(outcome.failures.any { it.contains("terminal state") }, outcome.failures.toString())
    }

    @Test
    fun `exceeding maxAttempts fails the fixture`() {
        val outcome = EvalRunner.evaluate(expected, result(attempts = expected.maxAttempts + 1))
        assertFalse(outcome.pass, "expected fail: $outcome")
        assertTrue(outcome.failures.any { it.contains("exceed ceiling") }, outcome.failures.toString())
    }

    @Test
    fun `a forbidden stage fails the fixture`() {
        val noPath = ExpectedOutcomeLoader.load(Path.of("fixtures/fixture-no-path/expected-outcome.yml"))
        val outcome =
            EvalRunner.evaluate(
                noPath,
                result(terminal = "UpgradeBlocker", stages = listOf("Analyzing", "Planning", "Applying"), attempts = 2),
            )
        assertFalse(outcome.pass, "Applying is forbidden on no-path: $outcome")
        assertTrue(outcome.failures.any { it.contains("forbidden stages") }, outcome.failures.toString())
    }

    @Test
    fun `report passes the 4-4 summary when all verdicts pass`() {
        val verdicts =
            listOf(
                EvalRunner.EvalOutcome("a", true),
                EvalRunner.EvalOutcome("b", true),
                EvalRunner.EvalOutcome("c", true),
                EvalRunner.EvalOutcome("d", true),
            )
        val report = EvalRunner.report(verdicts, "mock", emptyMap())
        assertTrue(report.contains("4/4 fixtures as expected"), report)
    }
}
