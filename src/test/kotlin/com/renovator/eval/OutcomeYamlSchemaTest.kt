package com.renovator.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OutcomeYamlSchemaTest {
    @Test
    fun `parses all four fixtures and validates their fields`() {
        val outcomes = ExpectedOutcomeLoader.loadAll()
        assertEquals(4, outcomes.size, "the eval dataset must have exactly four fixtures")
        val byName = outcomes.associateBy { it.fixture }

        val clean = byName.getValue("fixture-clean")
        assertEquals(ExpectedOutcome.ExpectedTerminalState.UpgradeComplete, clean.expectedTerminalState)
        assertEquals(
            listOf("org.apache.commons", "commons-lang3"),
            listOf(
                clean.goal.targets[0].groupId,
                clean.goal.targets[0].artifactId,
            ),
        )
        assertEquals("3.12.0", clean.goal.targets[0].fromVersion)
        assertEquals("3.14.0", clean.goal.targets[0].toVersion)
        assertEquals(listOf(ExpectedOutcome.Constraint.NoSnapshots), clean.goal.constraints)
        assertEquals(
            listOf(
                ExpectedOutcome.UpgradeStageName.Analyzing,
                ExpectedOutcome.UpgradeStageName.Planning,
                ExpectedOutcome.UpgradeStageName.Applying,
                ExpectedOutcome.UpgradeStageName.Verifying,
            ),
            clean.mustVisitStages,
        )
        assertTrue(clean.mustNotVisitStages.isEmpty(), "fixture-clean has no forbidden stages")
        assertEquals(6, clean.maxAttempts)
        assertEquals(listOf("BuildResult"), clean.requiredArtifacts)

        val removal = byName.getValue("fixture-api-removal")
        assertEquals(ExpectedOutcome.ExpectedTerminalState.UpgradeComplete, removal.expectedTerminalState)
        assertTrue(removal.mustVisitStages.contains(ExpectedOutcome.UpgradeStageName.Repairing))
        assertEquals(10, removal.maxAttempts)

        val conflict = byName.getValue("fixture-transitive-conflict")
        assertEquals(ExpectedOutcome.ExpectedTerminalState.UpgradeComplete, conflict.expectedTerminalState)
        assertTrue(conflict.mustVisitStages.contains(ExpectedOutcome.UpgradeStageName.Repairing))
        assertEquals(12, conflict.maxAttempts)

        val noPath = byName.getValue("fixture-no-path")
        assertEquals(ExpectedOutcome.ExpectedTerminalState.UpgradeBlocker, noPath.expectedTerminalState)
        assertEquals(listOf(ExpectedOutcome.UpgradeStageName.Applying), noPath.mustNotVisitStages)
        assertEquals(6, noPath.maxAttempts)
    }

    @Test
    fun `rejects unknown terminal state`() {
        val yaml =
            """
            fixture: bad
            goal:
              targets:
                - groupId: g
                  artifactId: a
                  fromVersion: "1"
                  toVersion: "2"
            expectedTerminalState: SomethingElse
            mustVisitStages: [Analyzing]
            maxAttempts: 3
            """.trimIndent()
        val tmp = writeTempYaml(yaml)
        assertThrows(Exception::class.java) { ExpectedOutcomeLoader.load(tmp) }
        Files.deleteIfExists(tmp)
    }

    @Test
    fun `rejects maxAttempts below 1`() {
        val yaml =
            """
            fixture: bad
            goal:
              targets:
                - groupId: g
                  artifactId: a
                  fromVersion: "1"
                  toVersion: "2"
            expectedTerminalState: UpgradeComplete
            mustVisitStages: [Analyzing]
            maxAttempts: 0
            """.trimIndent()
        val tmp = writeTempYaml(yaml)
        val thrown = assertThrows(Exception::class.java) { ExpectedOutcomeLoader.load(tmp) }
        assertTrue(thrown.message.orEmpty().contains("maxAttempts"), "error must name maxAttempts: $thrown")
        Files.deleteIfExists(tmp)
    }

    private fun writeTempYaml(yaml: String): Path {
        val tmp = Files.createTempFile("expected-outcome-", ".yml")
        Files.writeString(tmp, yaml)
        return tmp
    }
}
