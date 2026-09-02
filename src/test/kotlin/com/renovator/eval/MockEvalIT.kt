package com.renovator.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The mock eval gate (PLAN Task 6.1, 100% threshold): all four fixtures must
 * meet their expected outcomes with the canned LLM — this fails the build at
 * anything below 4/4 and writes the report (eval/reports/<date>-mock.md).
 */
class MockEvalIT {
    @Test
    fun `all four fixtures meet their expected outcomes`() {
        val outcomes = ExpectedOutcomeLoader.loadAll()
        val details = mutableMapOf<String, EvalRunner.RunResult>()
        val verdicts =
            outcomes.map { expected ->
                val result = EvalHarness.runFixture(expected.fixture)
                details[expected.fixture] = result
                EvalRunner.evaluate(expected, result)
            }

        val report = EvalRunner.report(verdicts, "mock", details)
        val reportPath = EvalRunner.writeReport(report, mode = "mock")
        println("MOCK EVAL REPORT: $reportPath")
        println(report)

        assertEquals(
            4,
            verdicts.count { it.pass },
            "mock eval must be 4/4:\n${verdicts.filter { !it.pass }}",
        )
    }
}
