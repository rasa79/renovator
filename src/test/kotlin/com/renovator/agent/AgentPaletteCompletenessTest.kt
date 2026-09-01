package com.renovator.agent

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.Agent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The palette table (PLAN §6) is the oracle: every action exists in the agent
 * metadata with the declared cost (D9) and an explicit output type; and exactly
 * one action class references UpgradeExecutor (enforcement §4.2 as a wiring check).
 */
class AgentPaletteCompletenessTest {
    private val metadata: Agent =
        AgentMetadataReader().createAgentMetadata(newAgent()) as Agent

    private fun newAgent() =
        RenovatorAgent(
            analyzeRepositoryAction =
                com.renovator.agent.actions
                    .AnalyzeRepositoryAction(),
            runBuildAction =
                com.renovator.agent.actions
                    .RunBuildAction(),
            applyValidatedChangesAction =
                com.renovator.agent.actions
                    .ApplyValidatedChangesAction(),
            finalizeUpgradeAction =
                com.renovator.agent.actions
                    .FinalizeUpgradeAction(),
            validatePlanAction =
                com.renovator.agent.actions
                    .ValidatePlanAction(),
            validatePatchAction =
                com.renovator.agent.actions
                    .ValidatePatchAction(),
            dryRunCompileAction =
                com.renovator.agent.actions
                    .DryRunCompileAction(),
            requestHumanDecisionAction =
                com.renovator.agent.actions
                    .RequestHumanDecisionAction(),
            llmActions =
                com.renovator.agent.actions
                    .LlmActions(),
        )

    /** PLAN §6 table: action name -> declared cost. */
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

    @Test
    fun `every palette action in the plan table exists with explicit precondition and output type`() {
        val actionNames = metadata.actions.map { it.name.split('.').lastOrNull() ?: it.name }.toSet()
        for (expected in paletteCosts.keys) {
            assertTrue(
                actionNames.contains(expected),
                "palette action '$expected' missing from agent metadata (have: ${actionNames.sorted()})",
            )
        }
        // every action declares a non-blank description + explicit output binding
        for (actionMeta in metadata.actions) {
            assertTrue(actionMeta.description.isNotBlank(), "action ${actionMeta.name} needs a description")
            assertTrue(actionMeta.outputs.isNotEmpty(), "action ${actionMeta.name} needs an explicit output type")
        }
    }

    @Test
    fun `only ApplyValidatedChangesAction references UpgradeExecutor`() {
        // §4.2 enforcement at the boundary, palette-wiring form (PLAN Task 3.1): in the
        // ACTION palette, exactly one action may touch the executor. (The L4 validator
        // also stages via UpgradeExecutor for its plan dry-run — it accepts only
        // Validated* and is not a palette action; noted in the phase-3 report.)
        val sourceRoot = Path.of("src/main/kotlin/com/renovator/agent/actions")
        val references =
            Files
                .walk(sourceRoot)
                .use { stream ->
                    stream
                        .filter { it.toString().endsWith(".kt") }
                        .filter { Files.readString(it).contains("UpgradeExecutor") }
                        .map { sourceRoot.relativize(it).toString() }
                        .sorted()
                        .toList()
                }
        assertEquals(
            listOf("ApplyValidatedChangesAction.kt"),
            references,
            "UpgradeExecutor must be referenced by exactly one palette action",
        )
    }

    @Test
    fun `every action declares a cost matching the plan table`() {
        // Reflective check on the @Action annotation values (metadata does not expose cost).
        val annotated =
            RenovatorAgent::class.java.declaredMethods
                .mapNotNull { m ->
                    val ann = m.getAnnotation(Action::class.java) ?: return@mapNotNull null
                    m.name to ann.cost
                }.toMap()
        assertEquals(paletteCosts.keys, annotated.keys, "annotated actions must match the table exactly")
        for ((name, cost) in paletteCosts) {
            assertEquals(cost, annotated[name] ?: -1.0, 0.0001, "cost for $name must match the §6 table")
        }
    }
}
