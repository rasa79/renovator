package com.renovator.validation

import com.renovator.config.RenovatorProperties
import com.renovator.domain.ChangeScope
import com.renovator.domain.Constraint
import com.renovator.domain.ValidationRejection
import com.renovator.domain.VersionChange
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DomainInvariantValidatorTest {
    private val lang3 = "org.apache.commons:commons-lang3"
    private val known = setOf("$lang3:3.12.0", "$lang3:3.14.0", "$lang3:4.0.0-SNAPSHOT", "$lang3:3.10.0")
    private val catalog = FakeVersionCatalog(known)

    private val validPom =
        """
        <?xml version="1.0"?>
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>g</groupId>
          <artifactId>a</artifactId>
          <version>1.0.0</version>
        </project>
        """.trimIndent()

    private fun validator(cfg: RenovatorProperties.Validation = RenovatorProperties().validation) = DomainInvariantValidator(catalog, cfg)

    private fun change(
        to: String,
        from: String = "3.12.0",
    ) = VersionChange("org.apache.commons", "commons-lang3", from, to, ChangeScope.DIRECT)

    @Test
    fun `rejects version that does not exist in the catalog`() {
        val rejection = validator().check(change("99.99.99"), pomXmlAfterEdit = validPom)
        assertNotNull(rejection)
        assertTrue(rejection!!.checkName.contains("version-exists"), "$rejection")
    }

    @Test
    fun `rejects downgrade even when version exists`() {
        val rejection = validator().check(change("3.10.0"), pomXmlAfterEdit = validPom)
        assertNotNull(rejection)
        assertTrue(rejection!!.checkName.contains("monotonic"), "$rejection")
    }

    @Test
    fun `rejects snapshot when disallowed`() {
        val rejection = validator().check(change("4.0.0-SNAPSHOT"), pomXmlAfterEdit = validPom)
        assertNotNull(rejection)
        assertTrue(rejection!!.checkName.contains("snapshots"), "$rejection")
    }

    @Test
    fun `accepts snapshot when constraint allows`() {
        val cfg = RenovatorProperties.Validation(allowSnapshots = true)
        val rejection = validator(cfg).check(change("4.0.0-SNAPSHOT"), pomXmlAfterEdit = validPom)
        assertNull(rejection)
        // ... but NoSnapshots in the goal's constraints beats config.
        val rejection2 =
            validator(cfg).check(
                change("4.0.0-SNAPSHOT"),
                constraints = listOf(Constraint.NoSnapshots),
                pomXmlAfterEdit = validPom,
            )
        assertNotNull(rejection2)
    }

    @Test
    fun `rejects pom missing modelVersion after edit`() {
        val broken = validPom.replace("<modelVersion>4.0.0</modelVersion>", "")
        val rejection = validator().check(change("3.14.0"), pomXmlAfterEdit = broken)
        assertNotNull(rejection)
        assertTrue(rejection!!.checkName.contains("model-version"), "$rejection")
    }

    @Test
    fun `rejects pom adding repository outside allowlist`() {
        val evil =
            validPom.replace(
                "</project>",
                "  <repositories><repository><id>evil</id><url>https://evil.example/maven2</url></repository></repositories>\n</project>",
            )
        val rejection = validator().check(change("3.14.0"), pomXmlAfterEdit = evil)
        assertNotNull(rejection)
        assertTrue(rejection!!.checkName.contains("repository-allowlist"), "$rejection")
    }

    @Test
    fun `accepts clean version bump`() {
        val rejection = validator().check(change("3.14.0"), pomXmlAfterEdit = validPom)
        assertNull(rejection)
    }
}
