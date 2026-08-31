package com.renovator.execution

import com.renovator.domain.UpgradePlan
import java.time.Instant
import java.util.UUID

/** What the executor returned: proof of what was applied, where, when. */
data class ExecutionReceipt(
    val receiptId: String = UUID.randomUUID().toString(),
    val appliedPlan: UpgradePlan,
    val workspace: WorkspaceRef,
    val appliedAt: Instant = Instant.now(),
)
