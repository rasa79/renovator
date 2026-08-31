package com.renovator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Authoring-time fixture sanity (PLAN §8.1-8.4): proves each fixture repo behaves
 * as the plan describes. These builds run in-place ON PURPOSE — they are the
 * authoring check; the RUNTIME never builds fixtures in place (D7; the sandbox
 * runner always works on a pristine copy, asserted by DockerSandboxRunnerIT).
 */
class FixtureSanityTest {
    private fun runMaven(
        fixture: String,
        vararg goals: String,
    ): Pair<Int, String> {
        val pb =
            ProcessBuilder(
                listOf("mvn", "-q", "-f", "fixtures/$fixture/pom.xml") + goals,
            ).redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        return Pair(process.waitFor(), output)
    }

    private fun yaml(fixture: String): String = Files.readString(Path.of("fixtures/$fixture/expected-outcome.yml"))

    @Test
    fun `fixture-clean baseline builds green`() {
        val (exit, output) = runMaven("fixture-clean", "verify")
        assertEquals(0, exit, "fixture-clean baseline must build green; output:\n$output")
    }

    @Test
    fun `fixture-clean expected-outcome parses`() {
        val y = yaml("fixture-clean")
        for (required in listOf(
            "fixture: fixture-clean",
            "expectedTerminalState: UpgradeComplete",
            "mustVisitStages:",
            "maxAttempts: 6",
            "org.apache.commons",
            "3.12.0",
            "3.14.0",
        )) {
            assertTrue(y.contains(required), "expected-outcome.yml must contain '$required'")
        }
        // §8.1: nothing beyond zero breakage — no Repairing stage, single build implied
        // by the bounded plan attempts (verified in full by OutcomeYamlSchemaTest, Task 1.5).
    }
}
