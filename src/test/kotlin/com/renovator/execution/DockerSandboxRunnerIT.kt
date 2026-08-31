package com.renovator.execution

import com.renovator.config.RenovatorProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.writeText

/**
 * Docker-backed IT (profile docker-it; never in the default verify).
 * These prove the deterministic judge end-to-end: green, red, kill, and
 * non-mutation of the source fixture directory (PLAN §8.5, Task 1.6).
 */
class DockerSandboxRunnerIT {
    private val runner =
        DockerSandboxRunner(RenovatorProperties().sandbox)
    private val copier = WorkspaceCopier()

    private fun hashTree(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .sorted()
                .forEach { file ->
                    digest.update(root.relativize(file).toString().toByteArray())
                    digest.update(Files.readAllBytes(file))
                }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `runs fixture-clean green and reports durationMs above zero`() {
        val ref = copier.copy(Path.of("fixtures/fixture-clean"))
        val result = runner.runBuild(ref, listOf("verify"), Duration.ofMinutes(10))
        assertTrue(result.success, "fixture-clean must build green; log head:\n${result.log.head.take(400)}")
        assertTrue(result.durationMs > 0)
        assertTrue(result.failedGoals.isEmpty())
    }

    @Test
    fun `captures compile failure from api-removal variant`() {
        // The deterministic RED case: fixture-api-removal with lang3 coordinates fails
        // to compile; the judge must name the plugin and the removed symbol.
        val ref = copier.copy(Path.of("fixtures/fixture-api-removal"))
        val swapped =
            Files
                .readString(ref.path.resolve("pom.xml"))
                .replace("commons-lang</groupId>", "org.apache.commons</groupId>")
                .replace("<artifactId>commons-lang</artifactId>", "<artifactId>commons-lang3</artifactId>")
                .replace("<version>2.6</version>", "<version>3.14.0</version>")
        Files.writeString(ref.path.resolve("pom.xml"), swapped)
        val result = runner.runBuild(ref, listOf("compile"), Duration.ofMinutes(10))
        assertFalse(result.success, "the swapped api-removal build must fail")
        assertTrue(
            result.failedGoals.any { it.contains("maven-compiler-plugin:compile") },
            "failedGoals must name the compiler goal: ${result.failedGoals}",
        )
        val fullLog = result.log.head + "\n" + result.log.tail
        assertTrue(
            fullLog.contains("StringEscapeUtils"),
            "the log excerpt must name the removed type",
        )
    }

    @Test
    fun `kills runaway container at hard timeout`(
        @TempDir tempDir: Path,
    ) {
        val stubLog = tempDir.resolve("stub.log")
        val stub = tempDir.resolve("stub-docker")
        stub.writeText(
            """
            #!/usr/bin/env bash
            echo "${'$'}@" >> "$stubLog"
            case "${'$'}1" in
              run) sleep 600 ;;
              kill|rm) exit 0 ;;
              *) exit 0 ;;
            esac
            """.trimIndent(),
        )
        // chmod via Runtime exec to avoid Files.setPosixFilePermissions differences.
        ProcessBuilder("chmod", "+x", stub.toString()).start().waitFor()

        val slowRunner = DockerSandboxRunner(RenovatorProperties().sandbox, dockerCommand = listOf(stub.toString()))
        val ref = copier.copy(Path.of("fixtures/fixture-clean"))
        val result = slowRunner.runBuild(ref, listOf("verify"), Duration.ofSeconds(2))

        assertFalse(result.success, "a runaway build must not report success")
        assertEquals(listOf("<timeout>"), result.failedGoals)
        assertEquals(2_000L, result.durationMs)
        val log = Files.readString(stubLog)
        assertTrue(log.contains("renovator-sandbox-"), "the runner must name its container: $log")
        assertTrue(log.contains("--memory=2048m") && log.contains("--cpus=2"), "resource limits must be passed: $log")
        assertTrue(log.contains("/m2"), "the named m2 cache volume must be mounted: $log")
    }

    @Test
    fun `never mutates the source fixture directory`() {
        val source = Path.of("fixtures/fixture-clean")
        val before = hashTree(source)
        val ref = copier.copy(source)
        runner.runBuild(ref, listOf("verify"), Duration.ofMinutes(10))
        assertEquals(before, hashTree(source), "the source fixture tree must be byte-identical after a build")
    }
}
