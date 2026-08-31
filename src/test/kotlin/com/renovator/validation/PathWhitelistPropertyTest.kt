package com.renovator.validation

import com.renovator.domain.CodePatch
import net.jqwik.api.Arbitraries
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.arbitraries.StringArbitrary
import org.junit.jupiter.api.Assertions.assertNotNull
import org.springframework.util.AntPathMatcher

/**
 * Property test over Layer 1's core invariant (PLAN §7 L1, Task 2.3):
 * ANY path that matches a forbidden pattern after normalization is rejected —
 * no matter how the attacker mixes separators, dot-segments, or redundant
 * slashes (>= 1000 tries).
 */
class PathWhitelistPropertyTest {
    @Provide
    fun pathStrings(): StringArbitrary =
        Arbitraries
            .strings()
            .withChars('a', 'b', 'c', 'd', 'e', '/', '\\', '.', '-', '_', ' ')
            .ofMinLength(0)
            .ofMaxLength(48)

    @Property(tries = 1000)
    fun `any path matching a forbidden pattern after normalization is rejected`(
        @ForAll("pathStrings") raw: String,
    ) {
        if (raw.isBlank()) {
            return // a blank path is not a valid patch domain-wise; out of the invariant's scope
        }
        val validator = PathWhitelistValidator()
        val normalized = validator.normalize(raw)
        if (normalized == null) {
            // Absolute/escaping paths are rejected outright (separate rule).
            assertNotNull(
                validator.check(CodePatch(raw, "diff", "j")),
                "absolute/escaping path must be rejected: $raw",
            )
            return
        }
        val matcher = AntPathMatcher()
        val forbiddenHit = validatorForbidden().any { matcher.match(it, normalized) }
        if (forbiddenHit) {
            val rejection = validator.check(CodePatch(raw, "diff", "j"))
            assertNotNull(rejection, "normalized '$normalized' (from '$raw') matches a forbidden pattern but was accepted")
        }
    }

    private fun validatorForbidden(): List<String> =
        // Defaults come from RenovatorProperties; kept in sync here so the property
        // test reads the same list the production validator uses.
        com.renovator.config
            .RenovatorProperties()
            .validation.forbiddenPaths
}
