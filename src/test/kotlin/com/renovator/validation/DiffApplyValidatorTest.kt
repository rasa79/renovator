package com.renovator.validation

import com.renovator.domain.CodePatch
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiffApplyValidatorTest {

    private val validator = DiffApplyValidator()

    private val filePath = "src/main/java/com/example/A.java"

    private fun diffBetween(original: String, modified: String): String =
        UnifiedDiffUtils
            .generateUnifiedDiff("a/$filePath", "b/$filePath", original.lines(), DiffUtils.diff(original.lines(), modified.lines()), 3)
            .joinToString("\n")

    private fun patch(diff: String, filePath: String = this.filePath) =
        CodePatch(filePath, diff, "test")

    private fun applied(patch: CodePatch, content: String): DiffApplyValidator.ApplyResult = validator.apply(patch, content)

    @Test
    fun `accepts diff that applies cleanly`() {
        val original = "class A {\n    int x;\n}\n"
        val modified = "class A {\n    int x;\n    int y;\n}\n"
        val result = applied(patch(diffBetween(original, modified)), original)
        assertTrue(result is DiffApplyValidator.ApplyResult.Applied, "clean diff must apply: $result")
        assertEquals(
            modified.trimEnd(),
            (result as DiffApplyValidator.ApplyResult.Applied).content.trimEnd(),
        )
    }

    @Test
    fun `rejects hunk whose context does not match, naming hunk index and expected line`() {
        val original = "class A {\n    int x;\n}\n"
        val modified = "class A {\n    int x;\n    int y;\n}\n"
        // Corrupt the diff's context: present a context that no longer matches.
        val corrupted = diffBetween(original, modified).replace("int x;", "int z;")
        val result = applied(patch(corrupted), original)
        assertTrue(result is DiffApplyValidator.ApplyResult.Rejected)
        val rejection = (result as DiffApplyValidator.ApplyResult.Rejected).rejection
        assertTrue(rejection.checkName.contains("hunk-1"), "checkName must name the hunk: ${rejection.checkName}")
        assertTrue(
            rejection.reason.contains("int z;"),
            "reason must name the expected context line: ${rejection.reason}",
        )
    }

    @Test
    fun `rejects modification of nonexistent file`() {
        val diff = diffBetween("class A {\n    int x;\n}\n", "class A {\n}\n")
        val result = applied(patch(diff), "") // file does not exist => no content to apply to
        assertTrue(result is DiffApplyValidator.ApplyResult.Rejected, "modifying a nonexistent file must be rejected")
    }

    @Test
    fun `accepts new-file diff`() {
        val newFileDiff =
            """
            --- /dev/null
            +++ b/src/main/kotlin/com/example/New.kt
            @@ -0,0 +1,2 @@
            +package com.example
            +val answer = 42
            """.trimIndent()
        val result = applied(patch(newFileDiff, "src/main/kotlin/com/example/New.kt"), "")
        assertTrue(result is DiffApplyValidator.ApplyResult.Applied, "new-file diff must apply: $result")
        assertEquals("package com.example\nval answer = 42", (result as DiffApplyValidator.ApplyResult.Applied).content)
    }

    @Test
    fun `rejects binary diff by scope`() {
        val result = applied(patch("Binary files a/logo.png and b/logo.png differ"), "x")
        assertTrue(result is DiffApplyValidator.ApplyResult.Rejected)
        assertTrue((result as DiffApplyValidator.ApplyResult.Rejected).rejection.checkName.contains("binary"))
    }

    @Test
    fun `rejects rename-only diff by scope`() {
        val diff = "diff --git a/A.java b/B.java\nrename from A.java\nrename to B.java"
        val result = applied(patch(diff), "class A {}\n")
        assertTrue(result is DiffApplyValidator.ApplyResult.Rejected)
        assertTrue((result as DiffApplyValidator.ApplyResult.Rejected).rejection.checkName.contains("rename"))
    }

    @Test
    fun `rejects malformed diff header`() {
        // No context lines and a garbage hunk: java-diff-utils either fails to parse
        // or yields zero hunks — both must surface as a typed rejection, never a crash.
        val result = applied(patch("@@ -1,1 +1 @@@\ngarbage that is not a diff"), "class A {}\n")
        assertTrue(result is DiffApplyValidator.ApplyResult.Rejected)
        val rejection = (result as DiffApplyValidator.ApplyResult.Rejected).rejection
        assertTrue(
            rejection.checkName in setOf("L2:malformed-diff", "L2:no-hunks"),
            "malformed header must be a typed rejection: $rejection",
        )
    }
}
