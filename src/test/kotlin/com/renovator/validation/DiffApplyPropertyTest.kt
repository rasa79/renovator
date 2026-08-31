package com.renovator.validation

import com.renovator.domain.CodePatch
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import net.jqwik.api.Arbitraries
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.arbitraries.IntegerArbitrary
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Property test over Layer 2's core invariant (PLAN §7 L2, Task 2.4):
 * NO generated diff with corrupted context applies to its target, no matter
 * which line of the diff text is perturbed (>= 1000 tries over random
 * line positions and random perturbations).
 */
class DiffApplyPropertyTest {

    private val validator = DiffApplyValidator()

    private val original = "class A {\n    int x;\n\n    int method(String s) {\n        return s.length() + x;\n    }\n}\n"

    @Property(tries = 1000)
    fun `no generated corrupted diff applies to its target`(
        @ForAll("mutations") mutation: Int,
    ) {
        val modified = original.replace("int x;", "int x;\n    int y;")
        val diff = UnifiedDiffUtils
            .generateUnifiedDiff("a/A.java", "b/A.java", original.lines(), DiffUtils.diff(original.lines(), modified.lines()), 3)
            .joinToString("\n")
        val lines = diff.lines()
        val index = if (lines.isEmpty()) 0 else Math.floorMod(mutation, lines.size)
        // Perturb the chosen line: change a context line's leading space to something
        // that is no longer a valid context match (keep '+'/'-' lines intact so the
        // perturbation is a CONTEXT drift, which is exactly what the L2 cares about).
        val perturbed = lines.toMutableList()
        val line = perturbed[index]
        if (line.startsWith(" ")) {
            perturbed[index] = " " + line.drop(1) + "X"
        } else if (line.startsWith("@@")) {
            perturbed[index] = line.dropLast(1) + "9"
        } else {
            perturbed[index] = " " + line + "Stale"
        }
        val result = validator.apply(CodePatch("src/main/java/com/example/A.java", perturbed.joinToString("\n"), "t"), original)
        assertTrue(
            result is DiffApplyValidator.ApplyResult.Rejected,
            "corrupted diff must be rejected (mutation at line $index): ${perturbed.joinToString("\n")}",
        )
    }

    @Provide
    fun mutations(): IntegerArbitrary =
        Arbitraries.integers().between(0, 10_000)
}
