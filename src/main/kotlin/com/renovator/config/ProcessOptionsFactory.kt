package com.renovator.config

import com.embabel.agent.core.EarlyTerminationPolicy
import com.embabel.agent.core.ProcessOptions

// LEARN[014] Honest termination: the attempt budget is a framework mechanism, not a convention
// Why this way: an agent that "keeps trying" with no exit costs a human an unkillable
//   process, and a budget that only the agent remembers is a budget nobody can audit.
//   Embabel 1.5.1 ships EarlyTerminationPolicy: maxActions(n), ON_STUCK, firstOf(...) —
//   attached to ProcessOptions.processControl, enforced by the framework between
//   actions, independent of our own code (verified in this task: the policy is an
//   interface method on the process, not an annotation we could forget). Our own
//   escalation (Blocked + UpgradeBlocker, Task 4.4) sits BELOW that ceiling:
//   max-attempts is the "I tried and failed" signal that makes the agent REPORT before
//   the framework CUTS. fixture-no-path is the fixture that proves the whole thing:
//   the target version 404s forever, so every proposal is L3-rejected and neither side
//   can be seduced into "one more try" — the only honest outcome is the blocker
//   carrying every typed rejection.
// Good sides: the budget is declared in config (renovator.budget.*) and applied by ONE
//   factory (ProcessOptionsFactory) — every run is bounded by construction; ON_STUCK
//   turns a planner dead-end into TERMINATED instead of a hung process; the blocker's
//   ledger is typed (ValidationRejection objects), so the human gets the exact reason.
// Drawbacks: the ledger must ride the STATE (LEARN[012]: clearBlackboard wipes the
//   board — a rejection loops back with a NEW Planning frame, attempts+1), and the
//   changing value is what makes each frame a DISTINCT node for the planner's search —
//   without the counter, the reject loop is a pure state cycle and the search
//   dead-ends before the escalation ever opens (verified in this task). The
//   escalation action needs @AchievesGoal to be planner-visible: a path ending in
//   WaitFor is not a path to BuildGreen, so without the marker the planner prefers
//   the doomed propose/validate loop over the 0.00-cost escalation. And a rejection
//   must be a STATE RETURN, not ReplanRequestedException: the framework's action
//   retry wraps the throw into an infinite blacklist/re-propose storm (observed:
//   3000+ validation outcomes for 3321-line run, one proposal).
// Concept: two circuit breakers in series — the agent's own (report), then the
//   platform's (cut). The human gets a report before the fuse blows.
// See also: PLAN §6, PLAN Task 4.4 (C-7), LEARN[012] (state-carried data), KL-01/08

/**
 * Unified process options (PLAN Task 4.4, C-7): every agent run is bounded. The
 * early-termination policy is `firstOf(maxActions(renovator.budget.max-actions),
 * ON_STUCK)` — the action budget is the VERIFIED framework mechanism
 * (LEARN[014], cited EarlyTerminationPolicy), and ON_STUCK converts a planner
 * dead-end into a typed TERMINATED status instead of a hung process. The agent's
 * OWN honesty ceiling (max-attempts → Blocked escalation) sits below this
 * framework backstop; the budget is not a convention either side can forget.
 */
class ProcessOptionsFactory(
    private val properties: RenovatorProperties = RenovatorProperties(),
) {
    fun processOptions(plannerType: com.embabel.agent.api.common.PlannerType? = null): ProcessOptions {
        val policy =
            EarlyTerminationPolicy
                .Companion
                .firstOf(
                    EarlyTerminationPolicy.Companion.maxActions(properties.budget.maxActions),
                    EarlyTerminationPolicy.Companion.ON_STUCK,
                )
        return ProcessOptions.DEFAULT
            .withProcessControl(
                ProcessOptions.DEFAULT
                    .processControl
                    .withEarlyTerminationPolicy(policy),
            ).let { if (plannerType == null) it else it.withPlannerType(plannerType) }
    }
}
