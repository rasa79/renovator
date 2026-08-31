package com.renovator.validation

import com.renovator.config.RenovatorProperties
import com.renovator.domain.CodePatch
import com.renovator.domain.CompileError
import com.renovator.execution.DockerSandboxRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompileErrorParserTest {
    @Test
    fun `parses javac error lines into typed errors`() {
        val sample =
            javaClass.getResource("/buildlogs/compile-failure.log")!!.readText()
        val errors = CompileErrorParser.parse(sample)
        assertTrue(errors.size >= 2, "the sample must yield at least two typed errors: $errors")
        val first = errors.first()
        assertTrue(first.filePath.endsWith("EscapeSqlFormatter.java"), "file path: ${first.filePath}")
        assertEquals(3, first.line)
        assertEquals(31, first.column)
        assertTrue(first.message.contains("does not exist"), "message: ${first.message}")
        val symbol = errors.first { it.line == 17 }
        assertEquals(16, symbol.column)
        assertTrue(symbol.message.contains("cannot find symbol"), "message: ${symbol.message}")
    }
}

class DryRunCompileValidatorTest {
    @Test
    fun `toggle off short-circuits to skipped result`() {
        // OFF must not touch Docker at all: a runner that would fail loudly proves it.
        val off =
            RenovatorProperties.Validation(
                dryRunCompile = RenovatorProperties.DryRunCompileMode.OFF,
            )
        val validator = DryRunCompileValidator(runner = failingRunner, validation = off)
        val result =
            validator.check(
                CodePatch("pom.xml", "diff", "j"),
                java.nio.file.Path
                    .of("fixtures/fixture-clean"),
            )
        assertTrue(result.skipped, "OFF must produce the skipped flag")
        assertTrue(result.success)
        assertTrue(result.errors.isEmpty())
    }

    private val failingRunner: DockerSandboxRunner =
        object : DockerSandboxRunner(RenovatorProperties().sandbox) {
            override fun runBuild(
                workspace: com.renovator.execution.WorkspaceRef,
                goals: List<String>,
                timeout: java.time.Duration,
            ): com.renovator.domain.BuildResult {
                error("Docker must not be touched when dry-run-compile=off")
            }
        }
}
