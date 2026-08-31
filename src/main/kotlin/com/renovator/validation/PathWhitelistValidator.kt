package com.renovator.validation

import com.renovator.config.RenovatorProperties
import com.renovator.domain.CodePatch
import com.renovator.domain.ValidationRejection
import org.springframework.util.AntPathMatcher

// LEARN[007] Normalize-then-match: why matching before normalizing is the classic whitelist bypass
// Why this way: a path whitelist is only as good as the checker's view of the path. If a
//   patch says "pom.xml" the whitelist happily says yes — but the filesystem reads
//   "pom.xml/../.git/config" and "src\main\..\..\secrets\local.env" as entirely
//   different files. Every bypass in the wild (CVE-style path traversal in file
//   uploads, archive extraction, patch tools) is a variant of "the guard and the
//   consumer disagree about what the path means". So: SEPARATORS TO '/', dot-segments
//   resolved, absolute rejected — THEN match; never match on the raw string.
// Good sides: the normalization is a pure function (testable with property tests over
//   arbitrary separator/dot-segment mixes); forbidden patterns are checked FIRST and
//   beat allowed patterns, so even a bad config can't un-forbid .git; the rejection
//   carries the offending content so the planner gets a usable signal.
// Drawbacks: lexical normalization is not the filesystem's truth — symlinks can still
//   alias paths (out of scope: the sandbox copy has no symlinks, the fixtures are
//   plain trees); AntPathMatcher differs slightly from gitignore semantics (patterns
//   are documented in RenovatorProperties, not invented per layer).
// Concept: think "canonicalize, then police the canonical form" — the same discipline
//   as validating an email address after trimming, or a URL after encoding. The
//   whitelist describes properties of the FINAL path, so the FINAL path is what you
//   hand it.
// See also: RenovatorProperties.Validation (pattern lists), PLAN §7 L1, LEARN[006]
class PathWhitelistValidator(
    private val validation: RenovatorProperties.Validation = RenovatorProperties().validation,
) {
    private val matcher = AntPathMatcher()

    /**
     * Normalize first, match second ([PathWhitelistValidator] KDoc):
     *  - separators (\ and /) become '/', repeated separators collapse
     *  - `.` segments drop, `..` segments resolve lexically
     *  - absolute paths (leading '/', Windows drives, escape past root) ⇒ null
     * Returns the normalized relative path or null when absolute/escaping.
     */
    fun normalize(rawPath: String): String? {
        var path = rawPath.replace('\\', '/')
        if (path.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(path)) {
            return null
        }
        val segments = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> {
                    Unit
                }

                ".." -> {
                    if (segments.isEmpty()) return null // escapes the root
                    segments.removeLast()
                }

                else -> {
                    segments.addLast(segment)
                }
            }
        }
        return segments.joinToString("/").ifEmpty { null }
    }

    /** L1: null = accepted; a [ValidationRejection] names the failing check. */
    fun check(patch: CodePatch): ValidationRejection? {
        val normalized = normalize(patch.filePath)
        if (normalized == null) {
            return rejection("absolute-or-escaping", patch.filePath)
        }
        for (forbidden in validation.forbiddenPaths) {
            if (matcher.match(forbidden, normalized)) {
                return rejection("forbidden:$forbidden", patch.filePath)
            }
        }
        for (allowed in validation.allowedPaths) {
            if (matcher.match(allowed, normalized)) {
                return null
            }
        }
        return rejection("not-whitelisted", patch.filePath)
    }

    private fun rejection(
        check: String,
        offending: String,
    ) = ValidationRejection(
        checkName = "L1:$check",
        reason = "path '$offending' is not permitted (normalized: ${normalize(offending) ?: "<absolute>"})",
        offendingContent = offending,
    )
}
