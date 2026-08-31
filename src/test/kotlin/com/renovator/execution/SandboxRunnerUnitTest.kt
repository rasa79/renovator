package com.renovator.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class WorkspaceCopierTest {
    private fun makeSource(): Path {
        val src = Files.createTempDirectory("renovator-src-")
        Files.createDirectories(src.resolve("src/main/java/com/example"))
        Files.writeString(src.resolve("src/main/java/com/example/A.java"), "class A {}\n")
        Files.createDirectories(src.resolve("target/classes"))
        Files.writeString(src.resolve("target/classes/A.class"), "stale-build-artifact")
        Files.createDirectories(src.resolve(".git/objects"))
        Files.writeString(src.resolve(".git/config"), "[core]")
        Files.writeString(src.resolve("pom.xml"), "<project/>\n")
        return src
    }

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
    fun `copies tree excluding target and dot-git`() {
        val src = makeSource()
        try {
            val ref = WorkspaceCopier().copy(src)
            assertTrue(Files.exists(ref.path.resolve("src/main/java/com/example/A.java")))
            assertTrue(Files.exists(ref.path.resolve("pom.xml")))
            assertFalse(Files.exists(ref.path.resolve("target")), "target/ must be excluded")
            assertFalse(Files.exists(ref.path.resolve(".git")), ".git must be excluded")
        } finally {
            src.toFile().deleteRecursively()
        }
    }

    @Test
    fun `source tree hashes unchanged after copy`() {
        val src = makeSource()
        try {
            val before = hashTree(src)
            WorkspaceCopier().copy(src)
            WorkspaceCopier().copy(src)
            assertEquals(before, hashTree(src), "copying must not mutate the source tree")
        } finally {
            src.toFile().deleteRecursively()
        }
    }
}

class ExcerptTest {
    @Test
    fun `truncates middle preserving head and tail within budget`() {
        val total = 50_000
        val text = "x".repeat(total - 1) + "\n"
        val excerpt = Excerpt.of(text)
        assertEquals(Excerpt.HEAD_BUDGET, excerpt.head.toByteArray().size)
        assertEquals(Excerpt.TAIL_BUDGET, excerpt.tail.toByteArray().size)
        assertEquals(total - Excerpt.HEAD_BUDGET - Excerpt.TAIL_BUDGET.toLong(), excerpt.truncatedBytes)
        assertEquals(text.take(Excerpt.HEAD_BUDGET), excerpt.head)
        assertEquals(text.takeLast(Excerpt.TAIL_BUDGET), excerpt.tail)
    }

    @Test
    fun `small text fits without truncation`() {
        val text = "small build log\n".repeat(10)
        val excerpt = Excerpt.of(text)
        assertEquals(text, excerpt.head)
        assertEquals("", excerpt.tail)
        assertEquals(0L, excerpt.truncatedBytes)
    }
}

class BuildResultParserTest {
    private fun log(name: String): String = javaClass.getResource("/buildlogs/$name")!!.readText()

    @Test
    fun `parses failed plugin goal from enforcer log sample`() {
        assertEquals(listOf("[maven-enforcer-plugin:enforce]"), BuildResultParser.failedGoals(log("enforcer-failure.log")))
    }

    @Test
    fun `parses compile failure from javac log sample`() {
        assertEquals(listOf("[maven-compiler-plugin:compile]"), BuildResultParser.failedGoals(log("compile-failure.log")))
    }
}
