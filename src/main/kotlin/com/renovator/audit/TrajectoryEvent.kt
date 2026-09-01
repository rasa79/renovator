package com.renovator.audit

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

/**
 * Typed trajectory events (PLAN Task 3.4, D14). One sealed hierarchy so every
 * line of the audit trail is structured; the JSONL store appends exactly these.
 * Phase 4 upgrades StageEntered to real @State transitions; the action-kind
 * events here are the Phase-3 baseline.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "eventType")
@JsonSubTypes(
    JsonSubTypes.Type(value = TrajectoryEvent.StageEntered::class, name = "StageEntered"),
    JsonSubTypes.Type(value = TrajectoryEvent.ProposalReceived::class, name = "ProposalReceived"),
    JsonSubTypes.Type(value = TrajectoryEvent.ValidationOutcome::class, name = "ValidationOutcome"),
    JsonSubTypes.Type(value = TrajectoryEvent.PlanAttempted::class, name = "PlanAttempted"),
    JsonSubTypes.Type(value = TrajectoryEvent.BuildObserved::class, name = "BuildObserved"),
    JsonSubTypes.Type(value = TrajectoryEvent.Escalated::class, name = "Escalated"),
    JsonSubTypes.Type(value = TrajectoryEvent.Completed::class, name = "Completed"),
    JsonSubTypes.Type(value = TrajectoryEvent.LlmCall::class, name = "LlmCall"),
    JsonSubTypes.Type(value = TrajectoryEvent.Resumed::class, name = "Resumed"),
)
sealed interface TrajectoryEvent {
    val at: Instant

    data class StageEntered(
        val stage: String,
        override val at: Instant = Instant.now(),
    ) : TrajectoryEvent

    data class ProposalReceived(
        val kind: String,
        val summary: String,
        override val at: Instant = Instant.now(),
    ) : TrajectoryEvent

    data class ValidationOutcome(
        val checkName: String,
        val accepted: Boolean,
        val reason: String,
        override val at: Instant = Instant.now(),
    ) : TrajectoryEvent

    data class PlanAttempted(
        val rationale: String,
        val stepCount: Int,
        override val at: Instant = Instant.now(),
    ) : TrajectoryEvent

    data class BuildObserved(
        val success: Boolean,
        val failedGoals: List<String>,
        val durationMs: Long,
        override val at: Instant = Instant.now(),
    ) : TrajectoryEvent

    data class Escalated(
        val question: String,
        override val at: Instant = Instant.now(),
    ) : TrajectoryEvent

    data class Completed(
        val terminal: String,
        override val at: Instant = Instant.now(),
    ) : TrajectoryEvent

    data class LlmCall(
        val action: String,
        val attempts: Int,
        val rejected: Boolean,
        val reason: String,
        override val at: Instant = Instant.now(),
    ) : TrajectoryEvent

    /** Kill-and-resume marker (PLAN Task 4.5, D10): a resumed run appends this
     *  first, so the audit trail shows exactly where the previous JVM's run ended
     *  and the continuation began — the same run id, one story. */
    data class Resumed(
        val reason: String,
        val frame: String,
        override val at: Instant = Instant.now(),
    ) : TrajectoryEvent
}
