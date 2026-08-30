package com.renovator.agent

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.core.Agent
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Task 0.3 wiring proof, modelled on the documented testing pattern
 * (reference/testing/page.adoc, v1.5.1: AgentMetadataReader + dummyAgentPlatform
 * + createAgentProcess(agent, ProcessOptions.DEFAULT.withPlannerType(...), Map.of(...))
 * + process.run().resultOfType(...)); the Kotlin-specific `as Agent` cast mirrors
 * the shipped Kotlin tests (ActionRetryTest) exactly.
 *
 * Uses PlannerType.UTILITY exactly like the documented retry-policy example:
 * UTILITY needs no Goal on the blackboard, which keeps this shell test about the
 * annotation pipeline, not about goal semantics (GOAP arrives in Task 3.1).
 */
class AgentShellWiringTest {
    @Test
    fun `agent metadata builds from annotations`() {
        val agent = AgentMetadataReader().createAgentMetadata(RenovatorAgent()) as Agent
        assertEquals("Renovator minimal shell — Task 0.3 wiring proof", agent.description)
        assertEquals(2, agent.actions.size)
        assertEquals(1, agent.goals.size)
    }

    @Test
    fun `echo action runs end to end on dummy platform`() {
        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val process =
            ap.createAgentProcess(
                AgentMetadataReader().createAgentMetadata(RenovatorAgent()) as Agent,
                ProcessOptions.DEFAULT.withPlannerType(PlannerType.GOAP),
                mapOf("goal" to UpgradeGoalStub("fixture-clean")),
            )
        val result = process.run().resultOfType(GoalAcknowledged::class.java)
        assertTrue(result.acknowledged)
        assertEquals("fixture-clean", result.target)
    }
}
