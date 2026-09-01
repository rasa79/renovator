package com.renovator.agent.actions

import com.renovator.config.RenovatorProperties
import com.renovator.domain.BuildResult
import com.renovator.domain.TestFailure
import com.renovator.domain.TestResult
import com.renovator.execution.DockerSandboxRunner
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Runs the fixture's `verify` in the sandbox and returns the typed verdict plus a
 * typed test result parsed from the Maven output (PLAN §6: `runBuild`). The judge
 * stays deterministic: exit code + parsed totals, no interpretation.
 */
@Component
class RunBuildAction(
    private val runner: DockerSandboxRunner = DockerSandboxRunner(RenovatorProperties().sandbox),
) {
    fun runBuild(workspaceRef: com.renovator.execution.WorkspaceRef): Pair<BuildResult, TestResult> {
        val build = runner.runBuild(workspaceRef, listOf("verify"), Duration.ofMinutes(10))
        return Pair(build, parseTests(build))
    }

    companion object {
        private val TOTAL = Regex("""Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)""")

        /** The surefire aggregate line is the LAST occurrence in the output. */
        fun parseTests(build: BuildResult): TestResult {
            val text = build.log.head + "\n" + build.log.tail
            val m = TOTAL.findAll(text).lastOrNull()
            if (m == null) {
                return TestResult(passed = 0, failed = 0, failures = emptyList())
            }
            val passed = m.groupValues[1].toInt()
            val failed = m.groupValues[2].toInt() + m.groupValues[3].toInt() // Failures + Errors
            return TestResult(passed = passed, failed = failed, failures = emptyList())
        }

        fun markFailedTests(
            build: BuildResult,
            failures: List<TestFailure>,
        ): TestResult = TestResult(passed = 0, failed = failures.size, failures = failures)
    }
}
