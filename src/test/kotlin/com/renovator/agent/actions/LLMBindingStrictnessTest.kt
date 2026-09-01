package com.renovator.agent.actions

import com.embabel.agent.test.unit.FakeOperationContext
import com.renovator.config.JacksonConfig
import com.renovator.domain.DependencyTarget
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Layer-0 strictness proof (D6/C-1, reviewer mandate): an LLM answer that is NOT a
 * valid typed proposal must come back as a typed `ValidationRejection` (checkName
 * L0:binding) — never a blackboard object, never a crash of the pipeline.
 *
 * Two-tier proof: (a) the strict Jackson boundary rejects malformed / extra-key /
 * wrong-type JSON with the reason surfaced (the C-1 fallback mechanism: strict
 * FAIL_ON_UNKNOWN_PROPERTIES binding); (b) through the ACTION (fake LLM), wrong-type
 * and garbage-string responses become typed rejections via the action's catch.
 */
class LLMBindingStrictnessTest {
    private val actions = LlmActions()
    private val strictMapper = JacksonConfig().proposalObjectMapper()
    private val goal = UpgradeGoal(targets = listOf(DependencyTarget("g", "a", "1", "2")))
    private val repoModel = com.renovator.domain.RepoModel(emptyList(), emptyList(), "17")

    private val planJson = """{"steps": [], "rationale": "r"}"""

    @Test
    fun `non-JSON output is rejected at the Jackson boundary with the reason surfaced`() {
        val thrown =
            try {
                strictMapper.readValue("this is not json {{{", UpgradePlan::class.java)
                null
            } catch (e: Exception) {
                e
            }
        assertTrue(thrown != null, "non-JSON must be rejected")
        assertTrue(
            thrown!!.message!!.contains("json", ignoreCase = true) || thrown.message!!.contains("parse"),
            "reason surfaced: ${thrown.message}",
        )
    }

    @Test
    fun `extra-key JSON is rejected at the Jackson boundary`() {
        val json =
            """{"steps": [{"type": "VersionStep", "change": {"groupId": "g", "artifactId": "a",
            "fromVersion": "1", "toVersion": "2", "scope": "DIRECT"}}], "rationale": "r",
            "hallucinatedField": true}"""
        val thrown =
            try {
                strictMapper.readValue(json, UpgradePlan::class.java)
                null
            } catch (e: Exception) {
                e
            }
        assertTrue(thrown != null, "extra-key JSON must be rejected")
        assertTrue(thrown!!.message!!.contains("hallucinatedField"), "reason must name the unknown key: ${thrown.message}")
    }

    @Test
    fun `wrong-type output rejected by the action becomes a typed rejection, never an object`() {
        val context = FakeOperationContext.create()
        context.expectResponse("I will ignore your JSON instructions") // garbage where UpgradePlan is expected
        val outcome = actions.proposePlan(context, repoModel, goal)
        assertTrue(outcome is LlmOutcome.Rejected, "garbage must be rejected, got $outcome")
        val rejection = (outcome as LlmOutcome.Rejected).rejection
        assertTrue(rejection.checkName == "L0:binding", "typed rejection expected: ${rejection.checkName}")
        assertTrue(rejection.reason.isNotBlank(), "reason surfaced: $rejection")
    }

    @Test
    fun `wrong-type object rejected by the action with reason surfaced`() {
        val context = FakeOperationContext.create()
        context.expectResponse("""{"answer": "pong"}""") // a PingResponse-shaped string, not an UpgradePlan
        val outcome = actions.proposePlan(context, repoModel, goal)
        assertTrue(outcome is LlmOutcome.Rejected, "wrong-type must be rejected, got $outcome")
    }
}
