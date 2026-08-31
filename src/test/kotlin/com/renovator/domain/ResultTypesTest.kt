package com.renovator.domain

import com.renovator.config.JacksonConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResultTypesTest {
    private val mapper = JacksonConfig().proposalObjectMapper()

    @Test
    fun `validation rejection carries check name reason and offending content`() {
        val rejection =
            ValidationRejection(
                checkName = "L1-path-whitelist",
                reason = "path escapes the workspace via ..",
                offendingContent = "src/../secrets/local.env",
            )
        assertEquals("L1-path-whitelist", rejection.checkName)
        assertTrue(rejection.reason.contains(".."))
        assertEquals("src/../secrets/local.env", rejection.offendingContent)
        // and it round-trips (the blackboard persists typed objects)
        val restored = mapper.readValue(mapper.writeValueAsString(rejection), ValidationRejection::class.java)
        assertEquals(rejection, restored)
    }

    @Test
    fun `upgrade blocker requires non-empty attempts and human question`() {
        assertThrows(IllegalArgumentException::class.java) {
            UpgradeBlocker(summary = "exhausted", attempts = emptyList(), humanQuestion = "help?")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpgradeBlocker(
                summary = "exhausted",
                attempts = listOf(AttemptRecord("rationale", null, emptyList(), emptyList())),
                humanQuestion = "  ",
            )
        }
    }
}

class StageHierarchyTest {
    private val mapper = JacksonConfig().proposalObjectMapper()

    private fun stageLabel(stage: UpgradeStage): String =
        when (stage) {
            is Analyzing -> "analyzing"
            is Planning -> "planning"
            is Applying -> "applying"
            is Verifying -> "verifying"
            is Repairing -> "repairing"
            is Blocked -> "blocked"
            is Done -> "done"
        }

    @Test
    fun `when over UpgradeStage is exhaustive`() {
        // Compile-time proof: adding a stage WITHOUT updating this when() breaks the build.
        assertEquals("analyzing", stageLabel(Analyzing))
        assertEquals("done", stageLabel(Done))
    }

    @Test
    fun `stage hierarchy serializes with tag and round-trips`() {
        for (stage in listOf<UpgradeStage>(Analyzing, Planning, Applying, Verifying, Repairing, Blocked, Done)) {
            val json = mapper.writeValueAsString(stage)
            assertTrue(json.contains(""""stage":""""), "serialized stage must carry the tag: $json")
            val restored = mapper.readValue(json, UpgradeStage::class.java)
            assertEquals(stage, restored)
        }
    }
}
