package com.renovator

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * README structure (PLAN Task 7.1, Appendix A): accessibility before architecture,
 * the verbatim "where it applies / where it doesn't" material, the bounded-claim
 * closing, and one sentence per user-visible KNOWN_LIMITATIONS entry.
 */
class ReadmeStructureTest {
    private val readme = Files.readString(Path.of("README.md"))

    private fun firstIndex(vararg needles: String): Int =
        needles.map { readme.indexOf(it).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }.min()

    @Test
    fun `what-is-this heading precedes any architecture heading`() {
        assertTrue(
            firstIndex("## What is this, in plain language") < firstIndex("## Architecture", "## Setup", "## Quickstart"),
            "the accessibility section must precede any architecture/technical content",
        )
    }

    @Test
    fun `the where-it-applies items appear verbatim`() {
        // Appendix A material, verbatim (grep -F): the six item titles + the closing
        // framing sentence.
        for (item in listOf(
            "Framework/language migrations (beyond dependency bumps).",
            "Database schema migration planning.",
            "IaC / configuration remediation.",
            "Anything with irreversible real-world side effects and no sandbox.",
            "Domains with no deterministic judge.",
            "Hard real-time / latency-critical paths.",
            "we know exactly which property makes this architecture safe, and we can name the classes of problems where it isn't.",
        )) {
            assertTrue(readme.contains(item), "README must contain verbatim: \"$item\"")
        }
        // The where-it-doesn't framing must be present (the two load-bearing properties).
        assertTrue(readme.contains("deterministic judge"), "the judge framing present")
        assertTrue(readme.contains("cheap reversibility"), "the reversibility framing present")
    }

    @Test
    fun `every user-visible KNOWN_LIMITATIONS entry has a README sentence`() {
        val kl = Files.readString(Path.of("KNOWN_LIMITATIONS.md"))
        val userVisible =
            kl.lines()
                .filter { it.startsWith("| KL-") }
                .filter { it.contains("user-visible: yes") }
                .mapNotNull { Regex("^\\| (KL-\\d+) \\|").find(it)?.groupValues?.get(1) }
        assertTrue(userVisible.isNotEmpty(), "at least one user-visible KL entry")
        for (id in userVisible) {
            assertTrue(
                readme.contains(id),
                "README must carry a sentence for the user-visible entry $id",
            )
        }
    }
}
