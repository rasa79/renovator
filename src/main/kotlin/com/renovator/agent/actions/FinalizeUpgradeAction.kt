package com.renovator.agent.actions

import com.renovator.domain.BuildResult
import com.renovator.domain.TestResult
import com.renovator.domain.UpgradeComplete
import com.renovator.domain.WorkspaceVerdict
import com.renovator.validation.ValidatedPlan
import org.springframework.stereotype.Component

/**
 * Produces the goal-achieving terminal object (PLAN §6: `finalizeUpgrade` carries
 * `@AchievesGoal`; the planner treats this output as the goal `BuildGreen`).
 * Preconditions (wired in Task 3.3): tests green; a compile check passed when
 * commit-candidacy is armed; a human decision when the gate is armed.
 */
@Component
class FinalizeUpgradeAction {
    fun finalize(
        plan: ValidatedPlan,
        verdict: WorkspaceVerdict,
    ): UpgradeComplete =
        UpgradeComplete(
            appliedSteps = plan.plan.steps,
            finalBuild = verdict.build,
            durationMs = verdict.build.durationMs,
        )
}
