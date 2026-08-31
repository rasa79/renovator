package com.renovator.validation

import com.renovator.config.RenovatorProperties
import com.renovator.domain.CodePatch
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PathWhitelistValidatorTest {
    private val validator = PathWhitelistValidator()
    private val cfg = RenovatorProperties().validation

    private fun check(path: String) = validator.check(CodePatch(path, "diff", "justification"))

    @Test
    fun `rejects dot-dot escape even when a later glob would allow it`() {
        val rejection = check("src/main/kotlin/../../secrets/token.txt")
        assertNotNull(rejection)
        assertTrue(rejection!!.reason.contains(".."), "reason must explain the escape: ${rejection.reason}")
    }

    @Test
    fun `rejects absolute path`() {
        assertNotNull(check("/etc/passwd"))
        assertNotNull(check("C:\\windows\\system32\\evil.exe"))
    }

    @Test
    fun `rejects dot-git path despite wildcard allow`() {
        // `**` alone is not in the allow list, but even if someone added `**`,
        // forbidden `.git/**` must beat it (forbidden-first ordering).
        val configured =
            PathWhitelistValidator(
                RenovatorProperties.Validation(
                    allowedPaths = listOf("**"),
                    forbiddenPaths = cfg.forbiddenPaths,
                ),
            )
        assertNotNull(configured.check(CodePatch(".git/config", "diff", "j")))
        assertNotNull(configured.check(CodePatch(".git/HEAD", "diff", "j")))
    }

    @Test
    fun `rejects shell script under src`() {
        val rejection = check("src/main/kotlin/com/example/RunMe.sh")
        assertNotNull(rejection)
        assertTrue(rejection!!.reason.contains(".sh"))
    }

    @Test
    fun `rejects env file at any depth`() {
        assertNotNull(check(".env"))
        assertNotNull(check("src/main/resources/.env"))
        assertNotNull(check("src/main/resources/.env.production"))
    }

    @Test
    fun `accepts pom dot-xml at root`() {
        assertNull(check("pom.xml"))
    }

    @Test
    fun `accepts new file under src main java`() {
        assertNull(check("src/main/java/com/example/new/File.java"))
    }

    @Test
    fun `normalizes windows separators and redundant dots`() {
        // A windows-style path to an allowed location must normalize and pass.
        assertNull(check("src\\main\\java\\com\\example\\A.java"))
        // Redundant ./ and // segments are collapsed before matching.
        assertNull(check("./src//main/./java/com/example/A.java"))
        // A windows-style escape must be caught by the same normalization.
        val rejection = check("src\\main\\java\\..\\..\\..\\etc\\passwd")
        assertNotNull(rejection)
    }
}
