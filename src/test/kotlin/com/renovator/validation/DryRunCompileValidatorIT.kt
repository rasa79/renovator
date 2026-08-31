package com.renovator.validation

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.renovator.domain.CodePatch
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Docker-backed L4 IT (profile docker-it): the dry-run compile inside the sandbox.
 * RED case: the api-removal swap must be rejected with typed diagnostics naming the
 * file and the removed symbol. GREEN case: a benign patch on fixture-clean compiles.
 */
class DryRunCompileValidatorIT {
    private val validator = DryRunCompileValidator()

    private fun unifiedDiff(
        path: String,
        original: String,
        modified: String,
    ): String =
        UnifiedDiffUtils
            .generateUnifiedDiff("a/$path", "b/$path", original.lines(), DiffUtils.diff(original.lines(), modified.lines()), 3)
            .joinToString("\n")

    @Test
    fun `rejects the api-removal breakage, naming the removed type`() {
        // Stage the BROKEN state in a temp copy: pom swapped to lang3 coordinates,
        // code still importing org.apache.commons.lang.StringEscapeUtils.
        val tmp = Files.createTempDirectory("renovator-l4-red")
        try {
            copyTree(Path.of("fixtures/fixture-api-removal"), tmp)
            val pom = tmp.resolve("pom.xml")
            Files.writeString(
                pom,
                Files
                    .readString(pom)
                    .replace("commons-lang</groupId>", "org.apache.commons</groupId>")
                    .replace("<artifactId>commons-lang</artifactId>", "<artifactId>commons-lang3</artifactId>")
                    .replace("<version>2.6</version>", "<version>3.14.0</version>"),
            )
            val file = tmp.resolve("src/main/java/com/example/removal/EscapeSqlFormatter.java")
            val original = Files.readString(file)
            // The patch itself is benign (a blank line + comment); the breakage is the
            // coordinate swap the plan already applied — L4's job is to SEE it.
            val modified =
                original.replace(
                    "public final class EscapeSqlFormatter {",
                    "public final class EscapeSqlFormatter {\n    // attempt 2",
                )
            val patch =
                CodePatch(
                    filePath = "src/main/java/com/example/removal/EscapeSqlFormatter.java",
                    unifiedDiff = unifiedDiff("src/main/java/com/example/removal/EscapeSqlFormatter.java", original, modified),
                    justification = "patch attempt 2",
                )
            val result = validator.check(patch, tmp)
            assertFalse(result.success, "the swapped api-removal fixture must NOT compile")
            // DRIFT absorbed (phase-1 report): the named type is StringEscapeUtils.
            assertTrue(
                result.errors.any { it.filePath.contains("EscapeSqlFormatter") && it.column > 0 },
                "typed errors must name the file: ${result.errors}",
            )
            assertTrue(
                result.errors.any { it.message.contains("cannot find symbol") || it.message.contains("StringEscapeUtils") },
                "typed errors must name the removed symbol: ${result.errors}",
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `accepts a benign patch on fixture-clean`() {
        val file = Path.of("fixtures/fixture-clean/src/main/java/com/example/clean/StringTools.java")
        val original = Files.readString(file)
        val modified = original.replace("\n}", "\n    // benign addition\n}")
        val patch =
            CodePatch(
                filePath = "src/main/java/com/example/clean/StringTools.java",
                unifiedDiff = unifiedDiff("src/main/java/com/example/clean/StringTools.java", original, modified),
                justification = "benign comment",
            )
        val result = validator.check(patch, Path.of("fixtures/fixture-clean"))
        assertTrue(result.success, "benign patch must compile: ${result.errors}")
        assertFalse(result.skipped)
        assertTrue(result.errors.isEmpty())
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
}
