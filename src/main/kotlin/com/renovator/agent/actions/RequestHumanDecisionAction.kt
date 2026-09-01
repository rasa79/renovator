package com.renovator.agent.actions

import com.renovator.domain.HumanDecision
import com.renovator.domain.UpgradeBlocker

/**
 * Human escalation (PLAN §6: `requestHumanDecision`, cost 0.00; Task 4.4 wires
 * the parking, C-3/C-6): the assembled [UpgradeBlocker] is the honest record of
 * WHY the plan space is exhausted, and `WaitFor.formSubmission` parks the
 * process in WAITING with a [HumanDecision] form — the Phase-5 REST layer is the
 * outside world that submits the decision and resumes the run (C-6 programmatic
 * submission path, verified in this phase).
 */
object RequestHumanDecisionAction {
    fun escalate(blocker: UpgradeBlocker): HumanDecision =
        com.embabel.agent.core.hitl.WaitFor
            .formSubmission(
                "Plan space exhausted: ${blocker.humanQuestion} (blocker: ${blocker.summary})",
                HumanDecision::class.java,
            )
}
