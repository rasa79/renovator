package com.renovator.agent.conditions

import com.embabel.agent.api.common.OperationContext
import com.renovator.domain.TestResult
import com.renovator.validation.ValidatedPatch
import com.renovator.validation.ValidatedPlan
import org.springframework.stereotype.Component

/**
 * `commitCandidacyArmed` guard (PLAN §6): the dry-run compile is the expensive L4
 * opinion (cost 0.80) — it is only plannable when a Validated* is waiting for
 * commit candidacy AND the tests are not green yet (i.e. there is something to
 * verify before finalize). Pure blackboard read, no side effects.
 */
@Component
class CommitCandidacyCondition {
    fun isArmed(context: OperationContext): Boolean {
        val hasValidated = context.objects.any { it is ValidatedPlan || it is ValidatedPatch }
        val lastTests = context.objects.filterIsInstance<TestResult>().lastOrNull()
        val testsNotGreen = lastTests == null || lastTests.failed > 0
        return hasValidated && testsNotGreen
    }
}
