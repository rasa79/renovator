package com.renovator.agent.conditions

import com.embabel.agent.api.common.OperationContext
import com.renovator.domain.BuildDiagnosis
import com.renovator.domain.HintKind

/**
 * Repair-lane gates (PLAN Tasks 4.3/4.4): the recovery loop reacts to a failure
 * in one of two ways, and the diagnosis's suggestedActions are the typed
 * contract that decides which lane the planner may take — PATCH_CODE means code
 * must change (the api-removal case: the patch lane, proposePatch/validatePatch),
 * while a transitive-pin or multi-hop diagnosis means the PLAN itself was wrong
 * (the transitive-conflict case: the replan lane, replan -> proposeUpgradePlan).
 * BOTH lanes are open while no diagnosis exists (the planner must be able to
 * plan the diagnose step itself — a @Condition only reads the CURRENT
 * blackboard, never a modeled future state; gating both lanes pre-diagnosis
 * closes the machine, verified in this phase). Once the diagnosis lands, the
 * hint sets are disjoint in every fixture: PATCH_CODE-only opens the patch lane,
 * PIN_TRANSITIVE/MULTI_HOP-only (no PATCH_CODE) opens the replan lane — so the
 * choice is deterministic and the cost-based tie the planner would otherwise
 * break arbitrarily never happens.
 */
class DiagnosisHintCondition {
    /** Safe blackboard read (proven pattern, same as CommitCandidacyCondition). */
    private fun diagnosis(context: OperationContext): BuildDiagnosis? = context.objects.filterIsInstance<BuildDiagnosis>().lastOrNull()

    fun suggestsPatch(context: OperationContext): Boolean =
        diagnosis(context)?.let { d -> d.suggestedActions.any { it.kind == HintKind.PATCH_CODE } } ?: true

    /**
     * Replan-lane gate: open while no diagnosis exists yet (the planner must be
     * able to plan past the repair decision point — conditions only see the
     * CURRENT board, LEARN[014]/phase-4 report evidence); once the diagnosis
     * lands, a transitive-pin/multi-hop hint opens the replan lane. Together
     * with [suggestsPatch] every fixture closes exactly one lane at the repair
     * decision: PATCH_CODE-only opens the patch lane, PIN_TRANSITIVE/MULTI_HOP-only
     * (no PATCH_CODE) opens the replan lane.
     */
    fun suggestsReplan(context: OperationContext): Boolean =
        diagnosis(context)
            ?.let { d -> d.suggestedActions.any { it.kind in setOf(HintKind.PIN_TRANSITIVE, HintKind.MULTI_HOP) } }
            ?: true
}
