package com.renovator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Mirrors the protocol checker's prompt rule at build time (PLAN §10.5): prompt-
 * shaped literals (triple-quoted strings >= 3 lines containing instruction verbs)
 * may exist ONLY under `src/main/resources/prompts/`. A prompt smuggled into an
 * action or test file fails the build before the hook ever sees the commit.
 */
class PromptLocationTest {
    private val verbs =
        listOf("you are", "your task", "reply with", "respond with", "summarize", "explain the", "generate a", "instruct", "answer in json")

    @Test
    fun `no prompt-shaped literals exist outside resources prompts`() {
        val offenders = mutableListOf<String>()
        for (root in listOf(Path.of("src/main/kotlin"), Path.of("src/test/kotlin"))) {
            Files.walk(root).use { stream ->
                stream
                    .filter { it.toString().endsWith(".kt") }
                    .forEach { file ->
                        val text = Files.readString(file)
                        for (match in Regex("\"\"\"([^\"]{20,}?)\"\"\"").findAll(text)) {
                            val snippet = match.groupValues[1]
                            if (snippet.lines().size >= 3 && verbs.any { snippet.lowercase().contains(it) }) {
                                offenders += "$file"
                            }
                        }
                    }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "prompt-shaped literals outside resources/prompts: $offenders",
        )
    }
}
