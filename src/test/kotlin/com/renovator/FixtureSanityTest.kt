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

    private fun copyTree(
        src: Path,
        dst: Path,
    ) {
        Files.walk(src).use { paths ->
            paths.filter { it != src }.forEach { from ->
                val to = dst.resolve(src.relativize(from).toString())
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to)
                } else {
                    Files.createDirectories(to.parent)
                    Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    @Test
    fun `fixture-api-removal baseline builds green`() {
        val (exit, output) = runMaven("fixture-api-removal", "verify")
        assertEquals(0, exit, "fixture-api-removal baseline must build green; output:\n$output")
    }

    @Test
    fun `after manual coordinate swap the build fails naming escapeSql`() {
        // Authoring-time proof of the breakage the agent will face (§8.2): copy to a
        // temp dir, sed the pom to the lang3 coordinates, compile must fail naming the
        // removed symbol. The runtime never does this in place (D7).
        val tmp = Files.createTempDirectory("renovator-fixture-swap")
        try {
            copyTree(Path.of("fixtures/fixture-api-removal"), tmp)
            val pom = tmp.resolve("pom.xml")
            val patched =
                Files
                    .readString(pom)
                    .replace("commons-lang</groupId>", "org.apache.commons</groupId>")
                    .replace("<artifactId>commons-lang</artifactId>", "<artifactId>commons-lang3</artifactId>")
                    .replace("<version>2.6</version>", "<version>3.14.0</version>")
            Files.writeString(pom, patched)
            val process =
                ProcessBuilder(
                    listOf("mvn", "-q", "-f", tmp.resolve("pom.xml").toString(), "compile"),
                ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            assertTrue(exit != 0, "the swapped build must fail to compile")
            // DRIFT absorbed (§13.3, phase-1 report): javac names the removed TYPE
            // (StringEscapeUtils) because the whole org.apache.commons.lang package is
            // absent from lang3 — the import fails before the method is ever checked.
            // The signal is still precise and nameable for the planner.
            assertTrue(
                output.contains("StringEscapeUtils"),
                "the compile error must name StringEscapeUtils; output was:\n$output",
            )
            println("SWAP-OUTPUT:\n${output.lines().takeLast(12).joinToString("\n")}")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `fixture-transitive-conflict baseline validates green`() {
        val (exit, output) = runMaven("fixture-transitive-conflict", "validate")
        assertEquals(0, exit, "baseline validate must be green (converged); output:\n$output")
    }

    @Test
    fun `direct bump deterministically fails convergence naming guava`() {
        // §8.3 mandatory verification: on a temp copy, bump the direct guava version
        // only; validate must fail naming guava (the judge must be able to say "no").
        val tmp = Files.createTempDirectory("renovator-conflict-bump")
        try {
            copyTree(Path.of("fixtures/fixture-transitive-conflict"), tmp)
            val pom = tmp.resolve("pom.xml")
            Files.writeString(
                pom,
                Files.readString(pom).replace("<version>31.0.1-jre</version>", "<version>33.4.8-jre</version>"),
            )
            val process =
                ProcessBuilder(
                    listOf("mvn", "-q", "-f", tmp.resolve("pom.xml").toString(), "validate"),
                ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            assertTrue(exit != 0, "the direct bump must fail validate")
            assertTrue(
                output.contains("Dependency convergence error") && output.contains("guava"),
                "the enforcer must name the convergence error and guava; output was:\n$output",
            )
            println(
                "BUMP-OUTPUT:\n${output.lines().filter { it.contains("guava") || it.contains("convergence") }.take(6).joinToString("\n")}",
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `fixture-no-path baseline builds green`() {
        val (exit, output) = runMaven("fixture-no-path", "verify")
        assertEquals(0, exit, "fixture-no-path baseline must build green; output:\n$output")
    }

    @Test
    fun `target version 99-99-99 does not exist on central`() {
        // §8.4: the fixture's target must 404 by construction, so Layer-3 rejection is
        // deterministic rather than a network flake the planner could accidentally pass.
        val client =
            java.net.http.HttpClient
                .newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build()
        val request =
            java.net.http.HttpRequest
                .newBuilder()
                .uri(
                    java.net.URI.create(
                        "https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/99.99.99/commons-lang3-99.99.99.pom",
                    ),
                ).method(
                    "HEAD",
                    java.net.http.HttpRequest.BodyPublishers
                        .noBody(),
                ).build()
        val status =
            client
                .send(
                    request,
                    java.net.http.HttpResponse.BodyHandlers
                        .discarding(),
                ).statusCode()
        assertEquals(404, status, "99.99.99 must never exist on Maven Central")
    }
}
