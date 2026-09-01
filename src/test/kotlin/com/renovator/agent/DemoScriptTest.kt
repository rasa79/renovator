package com.renovator.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * PLAN Task 4.3 acceptance: `scripts/demo-replan.sh` runs the two-hop fixture
 * (via the deterministic TwoHopReplanIT) and prints the §6.1 trace. The gate is
 * the script's exit code plus both plan attempts in its output — the real
 * trajectory lines are what the phase-4 report quotes.
 */
class DemoScriptTest {
    @Test
    fun `demo-replan sh exits 0 and its output contains both plan attempts`() {
        val script = Path.of("scripts/demo-replan.sh")
        require(script.toFile().exists()) { "demo script must exist at $script" }
        val proc =
            ProcessBuilder(
                "bash",
                script.toString(),
            ).directory(Path.of(".").toFile()).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(20, TimeUnit.MINUTES), "demo script timed out; output:\n$output")
        assertEquals(0, proc.exitValue(), "demo script must exit 0; output:\n$output")
        assertTrue(output.contains("single direct bump"), "attempt 1 printed:\n$output")
        assertTrue(
            output.contains("pin the transitive guava, then bump the direct dependency"),
            "attempt 2 printed:\n$output",
        )
        assertTrue(output.contains("BUILD OBSERVED: FAILED"), "the failed build is in the trace:\n$output")
        assertTrue(output.contains("BUILD OBSERVED: GREEN"), "the green build is in the trace:\n$output")
    }
}
