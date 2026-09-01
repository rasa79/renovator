package com.renovator.persistence

import com.renovator.domain.AttemptRecord
import com.renovator.domain.BuildDiagnosis
import com.renovator.domain.CodePatch
import com.renovator.domain.RepoModel
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import com.renovator.validation.ValidatedPlan

// LEARN[013] JVM process persistence: the repository SPI over typed snapshots —
// what it buys vs Sentinel's Postgres checkpointer
// Why this way: Sentinel (the parent project) checkpoints the BPMN instance in
//   Postgres — the process variable state is a relational row, and the engine
//   resumes by reading it back. Embabel's equivalent is AgentProcessRepository:
//   save/update/delete on the live AgentProcess (Blackboard + planner state).
//   A JVM process is NOT row-arm-movable: the blackboard can hold sealed data
//   classes, Kotlin objects, framework-internal refs — nobody can serialize the
//   process image faithfully. So the repository persists OUR TYPES only: the
//   state machine's frame (a data class — serializable by construction, LEARN[012])
//   plus the domain payloads (UpgradeGoal, RunRequest, the validated plan as
//   payload + proof check names). What we buy with that cut: the run survives a
//   JVM death (D10) — the typed snapshot var/runs/{runId}/process.json is the
//   ONLY survivor, and a fresh JVM re-seeds the machine from it (RunService).
//   What it costs: types must round-trip (the applied plan's ValidatedPlan is
//   reconstructed via its factory — digest rebinds from the same payload bytes,
//   so the executor's verifyProof still holds); and the workspace COPY (the
//   sandbox snapshot) is NOT part of it — the resume re-applies to a fresh copy
//   (D7: the source tree was never mutated, so re-application is deterministic).
// Good sides: the enforcement boundary is intact across the kill (proof digest
//   re-verified at apply); the repository is testable against mocks (typed
//   round-trip asserted without a JVM); the frame support is EXPLICIT (KL-08:
//   only Applying is resumable; a Repairing-frame kill restarts from scratch).
// Drawbacks: a killed Repairing frame's diagnostic is lost with the workspace
//   copy (see KL-08); the snapshot is not a transaction (a write is one file —
//   atomic enough for the demo, not a DB); and a resumed run re-runs the last
//   deterministic actions (apply) — idempotent here (fresh copy), not general.
// Concept: think "serialize the negotiation, not the table": the persistent
//   truth is the DECISION (validated plan, state frame), never the internal
//   planner's scratch. A BPMN checkpointer saves the process; we save the map.
// See also: PLAN Task 4.5 (D10, C-5), LEARN[012] (state-carried data),
//   KNOWN_LIMITATIONS KL-08 (resumable frame boundary)

/**
 * OUR run snapshot (PLAN Task 4.5, D10): the state machine's live frame plus the
 * payloads needed to CONTINUE it, written as `var/runs/{runId}/process.json` by
 * [JsonFileAgentProcessRepository].
 *
 * What it stores and why: a JVM process object cannot round-trip a process
 * image — the platform-side process is a live object graph (blackboard, planner
 * state, retry counters). What CAN be trusted is the raw domain payload: the
 * validated plan is stored as its payload + the proof's check names, and
 * [ValidatedPlan.create] rebinds the SAME sha256 digest on restore (the executor
 * recomputes and verifies it at apply time — the enforcement boundary is intact
 * across the kill). The workspace copy (WorkspaceSnapshot) is NOT stored: the
 * sandbox copy dies with the JVM; the resume re-applies the validated plan to a
 * fresh copy (D7 — the source tree was never mutated, so re-application is
 * deterministic).
 *
 * // TODO(review) KL-08: resume supports the frames that can be continued from a
 * // re-applicable payload (Applying). A run killed in Repairing holds the
 * // failed build's sandbox copy (already deleted with the JVM) and its
 * // diagnostic; resuming it requires re-running the build to reproduce the
 * // failure, which the repository does NOT attempt — such a run restarts from
 * // scratch. Pre-declared fallback per PLAN Task 4.5; the phase report states
 * // the boundary honestly (see KNOWN_LIMITATIONS.md).
 */
data class RunSnapshot(
    val runId: String,
    /** The state type the machine was in ("Applying", "Verifying", ...). */
    val frame: String,
    val goal: UpgradeGoal,
    val runRequest: RunRequest,
    val repoModel: RepoModel,
    /** The validated plan as payload + proof check names (digest rebinds on
     *  restore — same bytes, same sha256, executor verifies at apply time). */
    val planSteps: List<com.renovator.domain.PlanStep>,
    val planRationale: String,
    val proofCheckNames: List<String>,
    val pendingPatch: CodePatch? = null,
    val attempts: List<AttemptRecord> = emptyList(),
    val lastFailure: BuildDiagnosis? = null,
    val snapshotAt: String =
        java.time.Instant
            .now()
            .toString(),
) {
    /** Rebind the execution boundary on restore: same payload, same digest. */
    fun validatedPlan(): ValidatedPlan =
        ValidatedPlan.create(
            com.renovator.domain.UpgradePlan(steps = planSteps, rationale = planRationale),
            proofCheckNames,
        )

    companion object {
        const val CURRENT_SUPPORTED_FRAME = "Applying"
    }
}
