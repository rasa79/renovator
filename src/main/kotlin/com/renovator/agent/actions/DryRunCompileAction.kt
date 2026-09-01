package com.renovator.agent.actions

import com.renovator.config.RenovatorProperties
import com.renovator.domain.CodePatch
import com.renovator.domain.CompileCheckResult
import com.renovator.domain.RunRequest
import com.renovator.validation.DryRunCompileValidator
import com.renovator.validation.ValidatedPlan

/**
 * L4 dry-run compile in the sandbox (PLAN §6: `dryRunCompile`, cost 0.80 — the
 * judge's expensive second opinion, gated by the commit-candidacy condition).
 */
object DryRunCompileAction {
    private val validator: DryRunCompileValidator = DryRunCompileValidator()

    fun dryRun(patch: CodePatch, runRequest: RunRequest): CompileCheckResult =
        validator.check(patch, runRequest.repoPath)

    fun dryRunPlan(plan: ValidatedPlan, runRequest: RunRequest): CompileCheckResult =
        validator.checkPlan(plan, runRequest.repoPath)

    fun mode(): RenovatorProperties.DryRunCompileMode = RenovatorProperties().validation.dryRunCompile
}
