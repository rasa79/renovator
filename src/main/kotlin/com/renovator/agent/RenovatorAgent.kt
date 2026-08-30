package com.renovator.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent

/**
 * STUB types — Task 0.3 only (PLAN Task 0.3: "stub types local to this task and
 * replaced in Task 2.1 as planned work — stated here and there, so no TODO(review)
 * marker is needed or allowed"). Restated here for the same reason.
 */
data class UpgradeGoalStub(
    val target: String,
)

data class GoalAcknowledged(
    val acknowledged: Boolean,
    val target: String,
)

/**
 * Minimal agent shell, Task 0.3: proves that the Embabel annotation model
 * (`@Agent` with a deterministic `@Action` plus one goal-achieving action)
 * wires up end-to-end on the batch of capabilities verified in
 * [docs/verification-log.md] before ANY real domain code exists.
 *
 * The real agent (dedicated `UpgradeGoal`, full palette, GOAP planner) replaces
 * this class in Task 3.1.
 */
@Agent(description = "Renovator minimal shell — Task 0.3 wiring proof")
class RenovatorAgent {
    /**
     * Deterministic echo action: takes the (stub) goal from the blackboard and
     * acknowledges it. No LLM is involved — this action only proves the
     * annotation-to-metadata pipeline and parameter binding.
     */
    @Action(description = "Echo the proposed upgrade goal")
    fun echoGoal(goal: UpgradeGoalStub): GoalAcknowledged = GoalAcknowledged(acknowledged = true, target = goal.target)

    /**
     * Goal-achieving action: with `@AchievesGoal` the planner knows which
     * action's output is the process result; `resultOfType(GoalAcknowledged)` is
     * the documented way to extract it (reference/testing).
     */
    @Action(description = "Acknowledge the goal")
    @AchievesGoal(description = "Goal acknowledged")
    fun acknowledge(acknowledged: GoalAcknowledged): GoalAcknowledged = acknowledged
}
