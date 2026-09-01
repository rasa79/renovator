package com.renovator.agent.actions

import com.renovator.domain.RunRequest
import com.renovator.execution.TreeHasher
import com.renovator.execution.UpgradeExecutor
import com.renovator.execution.WorkspaceCopier
import com.renovator.execution.WorkspaceSnapshot
import com.renovator.validation.ValidatedPlan

/**
 * THE only action touching [UpgradeExecutor] (asserted by
 * AgentPaletteCompletenessTest: "only ApplyValidatedChangesAction references
 * UpgradeExecutor"). Stages the validated plan into a pristine copy and returns
 * the [WorkspaceSnapshot] the sandbox build reads (D7: the source tree is never
 * modified — the copy is).
 */
object ApplyValidatedChangesAction {
    private val executor: UpgradeExecutor = UpgradeExecutor()
    private val copier: WorkspaceCopier = WorkspaceCopier()
    fun apply(
        plan: ValidatedPlan,
        runRequest: RunRequest,
    ): WorkspaceSnapshot {
        val ref = copier.copy(runRequest.repoPath)
        executor.apply(plan, ref)
        return WorkspaceSnapshot(ref = ref, sourceHash = TreeHasher.of(runRequest.repoPath))
    }
}
