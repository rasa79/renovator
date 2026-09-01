package com.renovator.agent

import com.embabel.agent.api.annotation.Action
import com.renovator.agent.states.Analyzing
import com.renovator.agent.states.Applying
import com.renovator.agent.states.Blocked
import com.renovator.agent.states.Done
import com.renovator.agent.states.Planning
import com.renovator.agent.states.Repairing
import com.renovator.agent.states.Verifying
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The palette table (PLAN §6) is the oracle, now realized across the @State
 * machine (Task 4.1): every action exists as an @Action with the declared cost
 * (D9) on the agent (entry + conditions) or on a state class; and exactly one
 * action class references UpgradeExecutor (enforcement §4.2 as a wiring check).
 */
class AgentPaletteCompletenessTest {
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

    private fun stateClasses(): List<Class<*>> =
        listOf(
            Analyzing::class.java,
            Planning::class.java,
            Applying::class.java,
            Verifying::class.java,
            Repairing::class.java,
            Blocked::class.java,
            Done::class.java,
        )

    private fun annotatedMethods(clazz: Class<*>): Map<String, Double> =
        clazz.declaredMethods
            .mapNotNull { m ->
                val ann = m.getAnnotation(Action::class.java) ?: return@mapNotNull null
                m.name to ann.cost
            }.toMap()

    @Test
    fun `every palette action in the plan table exists with explicit precondition and output type`() {
        val allActions =
            annotatedMethods(RenovatorAgent::class.java) +
                stateClasses().flatMap { annotatedMethods(it).toList() }.toMap()
        assertTrue(allActions.keys.containsAll(paletteCosts.keys), "missing: ${paletteCosts.keys - allActions.keys}")
        for (clazz in stateClasses()) {
            assertTrue(
                annotatedMethods(clazz).isNotEmpty(),
                "state ${clazz.simpleName} must have at least one @Action",
            )
        }
    }

    @Test
    fun `only ApplyValidatedChangesAction references UpgradeExecutor`() {
        val sourceRoot = Path.of("src/main/kotlin/com/renovator")
        val references =
            Files
                .walk(sourceRoot)
                .use { stream ->
                    stream
                        .filter { it.toString().endsWith(".kt") }
                        .filter { Files.readString(it).contains("UpgradeExecutor") }
                        .map { sourceRoot.relativize(it).toString() }
                        .filter { it.startsWith("agent/") }
                        .sorted()
                        .toList()
                }
        assertEquals(
            listOf("agent/actions/ApplyValidatedChangesAction.kt"),
            references,
            "UpgradeExecutor must be referenced by exactly one palette action",
        )
    }

    @Test
    fun `every action declares a cost matching the plan table`() {
        val allActions =
            annotatedMethods(RenovatorAgent::class.java) +
                stateClasses().flatMap { annotatedMethods(it).toList() }.toMap()
        for ((name, cost) in paletteCosts) {
            assertEquals(cost, allActions[name] ?: -1.0, 0.0001, "cost for $name must match the §6 table")
        }
    }
}
