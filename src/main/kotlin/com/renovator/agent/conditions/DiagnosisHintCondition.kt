package com.renovator.agent.conditions

import com.embabel.agent.api.common.OperationContext
import com.renovator.domain.BuildDiagnosis
import com.renovator.domain.HintKind

/**
 * Patch-lane gate (PLAN Task 4.3): the repair loop reacts to a failure in one of
 * two ways, and the diagnosis's suggestedActions are the typed contract that
 * decides which lane the planner may take — PATCH_CODE means code must change
 * (the api-removal case: the patch lane, proposePatch/validatePatch), while a
 * transitive-pin or multi-hop diagnosis means the PLAN itself was wrong (the
 * transitive-conflict case: the replan lane, replan -> proposeUpgradePlan again).
 * Only the patch lane is gated; the replan lane is the always-open fallback
 * (Repairing.replan is condition-free) so the planner always has a complete path
 * to the goal even before the diagnosis exists — the @Condition mechanism only
 * reads the CURRENT blackboard, never a modeled future state (evidence in the
 * phase-4 report). Once the diagnosis lands, the open patch lane is the cheaper
 * route to the goal (1.10 vs 1.15) and wins; the transitive-conflict diagnosis
 * never opens it, so the replan proceeds.
 */
class DiagnosisHintCondition {
    /** Safe blackboard read (proven pattern, same as CommitCandidacyCondition). */
    private fun diagnosis(context: OperationContext): BuildDiagnosis? = context.objects.filterIsInstance<BuildDiagnosis>().lastOrNull()

    fun suggestsPatch(context: OperationContext): Boolean =
        diagnosis(context)?.let { d -> d.suggestedActions.any { it.kind == HintKind.PATCH_CODE } } ?: false
}
