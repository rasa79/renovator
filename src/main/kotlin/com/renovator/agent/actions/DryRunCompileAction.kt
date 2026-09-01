package com.renovator.agent.actions

import com.renovator.config.RenovatorProperties
import com.renovator.domain.CodePatch
import com.renovator.domain.CompileCheckResult
import com.renovator.domain.RunRequest
import com.renovator.validation.DryRunCompileValidator
import org.springframework.stereotype.Component

/**
 * L4 dry-run compile in the sandbox (PLAN §6: `dryRunCompile`, cost 0.80 — the
 * judge's expensive second opinion, gated by the commit-candidacy condition in
 * Task 3.3). Honors `renovator.validation.dry-run-compile`.
 */
@Component
class DryRunCompileAction(
    private val validator: DryRunCompileValidator = DryRunCompileValidator(),
) {
    fun dryRun(
        patch: CodePatch,
        runRequest: RunRequest,
    ): CompileCheckResult = validator.check(patch, runRequest.repoPath)

    fun dryRunPlan(
        plan: com.renovator.validation.ValidatedPlan,
        runRequest: RunRequest,
    ): CompileCheckResult = validator.checkPlan(plan, runRequest.repoPath)

    fun mode(): RenovatorProperties.DryRunCompileMode = RenovatorProperties().validation.dryRunCompile
}
