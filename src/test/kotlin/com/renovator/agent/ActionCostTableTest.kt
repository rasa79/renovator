package com.renovator.agent

import com.embabel.agent.api.annotation.Action
import com.renovator.config.RenovatorProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §6 table costs enforced by reflection (D9): every palette action declares
 * the table's cost, and sandbox operations are the expensive ones so the planner
 * prefers failing cheap.
 */
class ActionCostTableTest {
    private val paletteCosts =
        mapOf(
            "analyzeRepository" to 0.05,
            "proposeUpgradePlan" to 0.30,
            "validatePlan" to 0.05,
            "applyValidatedChanges" to 0.10,
            "runBuild" to 0.60,
            "diagnoseFailure" to 0.30,
            "proposePatch" to 0.30,
            "validatePatch" to 0.05,
            "dryRunCompile" to 0.80,
            "requestHumanDecision" to 0.00,
            "finalizeUpgrade" to 0.05,
        )

    private val annotated: Map<String, Double> =
        RenovatorAgent::class.java.declaredMethods
            .mapNotNull { m ->
                val ann = m.getAnnotation(Action::class.java) ?: return@mapNotNull null
                m.name to ann.cost
            }.toMap()

    @Test
    fun `every action declares a cost matching the plan table`() {
        assertEquals(paletteCosts.keys, annotated.keys, "annotated actions must match the §6 table exactly")
        for ((name, cost) in paletteCosts) {
            assertEquals(cost, annotated[name] ?: -1.0, 0.0001, "cost for $name must match the §6 table")
        }
    }

    @Test
    fun `expensive actions (sandbox) declare cost at least 0-6`() {
        for (name in listOf("runBuild", "dryRunCompile")) {
            assertTrue((annotated[name] ?: 0.0) >= 0.6, "$name must be expensive per D9")
        }
        // cheap validators must stay cheap (they gate every proposal).
        for (name in listOf("validatePlan", "validatePatch", "analyzeRepository")) {
            assertTrue((annotated[name] ?: 1.0) <= 0.05, "$name must be cheap per D9")
        }
    }

    @Test
    fun `defaults are coherent with the gate config`() {
        // The default approval gates are disarmed so mock flows need no human; the
        // default dry-run mode is on-commit-candidate per §7 L4.
        assertEquals(false, RenovatorProperties().approvals.plan)
        assertEquals(false, RenovatorProperties().approvals.commitCandidate)
        assertEquals(RenovatorProperties.DryRunCompileMode.ON_COMMIT_CANDIDATE, RenovatorProperties().validation.dryRunCompile)
    }
}
