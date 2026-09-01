package com.renovator.agent.conditions

import com.renovator.config.RenovatorProperties

/**
 * `approvalGateArmed` guard (PLAN §6, D11): whether any HITL approval gate is
 * armed by config (renovator.approvals.*). Defaults: both disarmed, so Phase 3-4
 * mock flows reach UpgradeComplete without a human; Phase 5 arms the gates.
 */
class GateArmedCondition(
    private val properties: RenovatorProperties = RenovatorProperties(),
) {
    fun isArmed(): Boolean = properties.approvals.plan || properties.approvals.commitCandidate
}
