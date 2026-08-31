package com.renovator.domain

import com.renovator.config.JacksonConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProposalTypesTest {
    private val mapper = JacksonConfig().proposalObjectMapper()

    private val samplePlan =
        UpgradePlan(
            steps =
                listOf(
                    PlanStep.VersionStep(
                        VersionChange("com.google.guava", "guava", "31.0.1-jre", "33.4.8-jre", ChangeScope.MANAGEMENT),
                    ),
                    PlanStep.VersionStep(
                        VersionChange("com.google.guava", "guava", "31.0.1-jre", "33.4.8-jre", ChangeScope.DIRECT),
                    ),
                ),
            rationale = "pin the transitive guava, then bump the direct dependency",
        )

    private val samplePatch =
        CodePatch(
            filePath = "src/main/java/com/example/removal/EscapeSqlFormatter.java",
            unifiedDiff = "diff --git a/... b/...\n--- a/...\n+++ b/...\n@@ -1,3 +1,3 @@\n ...",
            justification = "the escapeSql call was removed from lang3; use a local escape",
        )

    @Test
    fun `every proposal type round-trips through Jackson`() {
        for (proposal in listOf<Any>(samplePlan, samplePatch)) {
            val json = mapper.writeValueAsString(proposal)
            val restored = mapper.readValue(json, proposal.javaClass)
            assertEquals(proposal, restored, "round-trip must be lossless for $proposal")
        }
        // Goal + constraints are part of the proposal family too.
        val goal =
            UpgradeGoal(
                targets = listOf(DependencyTarget("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0")),
                constraints = listOf(Constraint.NoSnapshots, Constraint.MaxHops(2)),
            )
        val restored = mapper.readValue(mapper.writeValueAsString(goal), UpgradeGoal::class.java)
        assertEquals(goal, restored)
    }

    @Test
    fun `rejects unknown keys on deserialize`() {
        val json =
            """
            {"targets": [{"groupId": "g", "artifactId": "a", "fromVersion": "1", "toVersion": "2",
                          "hallucinatedField": true}], "constraints": []}
            """.trimIndent()
        val thrown = assertThrows(Exception::class.java) { mapper.readValue(json, UpgradeGoal::class.java) }
        assertTrue(thrown.message.orEmpty().contains("hallucinatedField"), "error must name the unknown key")
    }

    @Test
    fun `PlanStep sealed hierarchy serializes with type tag`() {
        val json = mapper.writeValueAsString(samplePlan)
        assertTrue(json.contains(""""type":"VersionStep""""), "the plan JSON must carry the PlanStep type tag")
        // A patched step gets its own tag.
        val patched = UpgradePlan(steps = listOf(PlanStep.PatchStep(samplePatch)), rationale = "patch")
        val patchedJson = mapper.writeValueAsString(patched)
        assertTrue(patchedJson.contains(""""type":"PatchStep""""))
    }

    @Test
    fun `rejects blank groupId or version`() {
        assertThrows(IllegalArgumentException::class.java) {
            DependencyTarget("", "a", "1", "2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DependencyTarget("g", "a", " ", "2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VersionChange("g", "a", "1", "", ChangeScope.DIRECT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpgradePlan(steps = emptyList(), rationale = "empty")
        }
    }
}
