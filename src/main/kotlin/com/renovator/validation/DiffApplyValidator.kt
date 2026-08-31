package com.renovator.validation

import com.github.difflib.UnifiedDiffUtils
import com.renovator.domain.CodePatch
import com.renovator.domain.ValidationRejection

// LEARN[008] A real diff library, never regex: what unified-diff context lines are FOR
// Why this way: a unified diff is a machine-readable contract between "what the code
//   looked like when the model wrote the patch" and "what the code looks like now".
//   The context lines ARE the deterministic judge of "does this patch still apply" —
//   not a hint, not decoration. A regex that approximates '@@ -a,b +c,d @@' can parse
//   the envelope, but it cannot verify the CONTEXT: three lines that look like a hunk
//   will always look like a hunk. java-diff-utils does the real work (fuzz matching is
//   NOT what we want either — a wrong context must be rejected, not silently healed).
// Good sides: rejection reasons name the exact hunk index and the expected context
//   line, so the planner gets the same precise signal the validator had; new-file
//   diffs (0-line source) fall out of the same code path; binary/rename/deletion
//   have explicit, tested scope rejections (KL-10).
// Drawbacks: java-diff-utils 4.17 handles one file per diff (a multi-file "diff"
//   is rejected as malformed — the LLM emits ONE CodePatch per file by design);
//   CRLF files must be normalized before apply (documented in the caller); and
//   fuzz/offset tolerance is off by design — a stale patch fails loudly instead of
//   applying near-misses, which is exactly the behavior recovery needs (KL-04).
// Concept: think of L2 as "type check the patch": the diff type-checks against the
//   current file, and a failed type check is an observation, not a retry.
// See also: PLAN §7 L2, LEARN[007] (normalize first), LEARN[006] (validation seals)
class DiffApplyValidator {
    /** Result of applying a patch: the applied content, or a typed rejection. */
    sealed interface ApplyResult {
        data class Applied(
            val content: String,
        ) : ApplyResult

        data class Rejected(
            val rejection: ValidationRejection,
        ) : ApplyResult
    }

    /**
     * L2: parse with java-diff-utils, apply in-memory to [currentContent] with
     * explicit per-hunk context verification. Clean apply → [ApplyResult.Applied]
     * (the new content); mismatch → [ApplyResult.Rejected] naming the hunk index
     * and the expected context line.
     *
     * // TODO(review) KL-10: binary diffs, rename-only diffs, deletion diffs (and
     * // multi-file diffs, which java-diff-utils parses as malformed input) are
     // rejected by scope — the plan pre-declared this cut in §12/KL-10.
     */
    fun apply(
        patch: CodePatch,
        currentContent: String,
    ): ApplyResult {
        val diff = patch.unifiedDiff
        if (diff.contains("Binary files") && diff.contains("differ")) {
            return rejected("binary-by-scope", "binary diffs are rejected by scope", firstLine(diff))
        }
        if (diff.contains("rename from") || diff.contains("rename to")) {
            return rejected("rename-by-scope", "rename-only diffs are rejected by scope", "rename from/to")
        }
        if (patch.unifiedDiff.contains("\u0000")) {
            return rejected("binary-by-scope", "NUL byte in diff: binary content by scope", "<binary>")
        }

        val lines = diff.lines()
        val parsed =
            try {
                UnifiedDiffUtils.parseUnifiedDiff(lines)
            } catch (e: Exception) {
                return rejected("malformed-diff", "unified diff could not be parsed: ${e.message}", firstLine(diff))
            }

        val deltas = parsed.deltas
        if (deltas.isEmpty()) {
            return rejected("no-hunks", "diff contains no hunks", firstLine(diff))
        }
        val sourceHasLines = deltas.any { it.source.lines.isNotEmpty() }
        val targetHasLines = deltas.any { it.target.lines.isNotEmpty() }
        if (!targetHasLines) {
            return rejected("deletion-by-scope", "deletion diffs are rejected by scope", firstLine(diff))
        }
        if (deltas.all { it.source.lines == it.target.lines }) {
            return rejected("rename-only-by-scope", "rename-only diffs are rejected by scope", "no content changes")
        }

        // The diff's target file must agree with the patch's filePath (a path the
        // whitelist approved but the diff writes elsewhere is a mismatch).
        val targetPathLine = lines.lastOrNull { it.startsWith("+++ ") } ?: ""
        val targetPath =
            targetPathLine
                .substring(4)
                .trim()
                .removePrefix("b/")
                .removePrefix("a/")
        if (targetPath.isNotBlank() && targetPath != patch.filePath.replace('\\', '/')) {
            return rejected(
                "path-mismatch",
                "diff target '$targetPath' does not match patch filePath '${patch.filePath}'",
                targetPathLine,
            )
        }

        // Apply with explicit per-hunk context verification so rejections NAME the
        // hunk index and the expected context line.
        val original = if (sourceHasLines) currentContent.lines() else emptyList()
        var cursor = 0
        val output = mutableListOf<String>()
        for ((index, delta) in deltas.withIndex()) {
            val start = (delta.source.position - 1).coerceAtLeast(0)
            if (start < cursor) {
                return rejected("hunk-${index + 1}", "hunk overlaps a previously applied region", "")
            }
            val context = delta.source.lines
            for (k in context.indices) {
                val expected = if (start + k < original.size) original[start + k] else "<eof>"
                if (expected != context[k]) {
                    return rejected(
                        "hunk-${index + 1}",
                        "expected context line '${context[k].take(80)}' not found at line ${start + k + 1}",
                        context[k],
                    )
                }
            }
            output.addAll(original.subList(cursor, start))
            output.addAll(delta.target.lines)
            cursor = start + context.size
        }
        output.addAll(original.subList(cursor, original.size))
        return ApplyResult.Applied(output.joinToString("\n"))
    }

    private fun rejected(
        check: String,
        reason: String,
        offending: String,
    ): ApplyResult.Rejected =
        ApplyResult.Rejected(
            ValidationRejection(checkName = "L2:$check", reason = reason, offendingContent = offending),
        )

    private fun firstLine(diff: String): String = diff.lines().firstOrNull().orEmpty()
}
