package com.renovator.api

import com.renovator.domain.HumanDecision
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** POST /api/runs/{id}/decisions body (PLAN Task 5.3). */
data class DecisionRequest(
    val approved: Boolean,
    val comment: String = "",
)

/**
 * HITL decision surface (PLAN Task 5.3): the pending decision payload and the
 * submission that continues a parked run. Typed errors only (same handler as
 * RunController).
 */
@RestController
class DecisionController(
    private val runs: RunService,
) {
    @GetMapping("/api/runs/{id}/pending-decision")
    fun pendingDecision(
        @PathVariable id: String,
    ): ResponseEntity<PendingDecision> {
        val pending =
            runs.pendingDecision(id)
                ?: throw IllegalArgumentException("run '$id' has no pending decision")
        return ResponseEntity.ok(pending)
    }

    @PostMapping("/api/runs/{id}/decisions")
    fun decide(
        @PathVariable id: String,
        @RequestBody request: DecisionRequest,
    ): ResponseEntity<RunStatus> {
        val status =
            runs.submitDecision(
                runId = id,
                decision = HumanDecision(approved = request.approved, comment = request.comment),
            )
        return ResponseEntity.ok(status)
    }
}
