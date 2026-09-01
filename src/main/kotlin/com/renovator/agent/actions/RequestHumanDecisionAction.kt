package com.renovator.agent.actions

import com.renovator.domain.UpgradeBlocker
import org.springframework.stereotype.Component

/**
 * Human escalation (PLAN §6: `requestHumanDecision`, cost 0.00). In Phase 3/4 it
 * carries the assembled [UpgradeBlocker]; the `WaitFor` parking (C-3/C-6) is
 * wired in Task 4.4 (until then the action is the honest terminal object's
 * carrier, and the REST/HITL layer in Phase 5 resolves it).
 */
@Component
class RequestHumanDecisionAction {
    fun request(blocker: UpgradeBlocker): UpgradeBlocker = blocker
}
