package com.renovator.audit

import java.time.Instant
import java.util.UUID

/**
 * Run-scoped trajectory emission (PLAN Task 3.4 store; wired into the @State
 * machine in Task 4.2). The state machine instances are framework-constructed
 * with no DI, so the run context rides a reseatable holder — the same pattern as
 * LlmChannel. The entry action mints a run id when none is set; tests may pin one
 * (RepairLoopIT reads back `var/runs/{id}/trajectory.jsonl`).
 */
object RunAudit {
    private val store = TrajectoryStore()

    @Volatile
    var runId: String? = null

    fun emit(event: TrajectoryEvent) {
        com.renovator.observability.RenovatorMetrics.observe(event, runId)
        runId?.let { id ->
            val seq = store.append(id, event)
            TrajectoryBus.publish(
                TrajectoryBus.PublishedEvent(
                    runId = id,
                    seq = seq,
                    line =
                        com.renovator.validation.ProposalJson.mapper.writeValueAsString(
                            PublishLine(seq = seq, event = event),
                        ),
                ),
            )
        }
    }

    /** Entry seam: mint a fresh id per run (only when the test has not pinned one). */
    fun ensureRunId(): String {
        val existing = runId
        if (existing != null) {
            return existing
        }
        return newRunId().also { runId = it }
    }

    fun newRunId(): String = "run-${Instant.now().toEpochMilli()}-${UUID.randomUUID().toString().take(8)}"

    fun clear() {
        runId = null
    }

    fun store(): TrajectoryStore = store

    /** Same shape as the store's envelope: {"seq":N,"event":{...}} — the bus
     *  delivers bytes identical to what the file holds (the SSE data lines). */
    private data class PublishLine(
        val seq: Long,
        val event: TrajectoryEvent,
    )
}
