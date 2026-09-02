package com.renovator.audit

import java.util.concurrent.ConcurrentHashMap

/**
 * Single-run enforcement (KL-01, PLAN §10.2/§12): one agent run at a time. A
 * second concurrent run is rejected with a typed error; the registry keeps no
 * process state (that is the persistence layer's job, Phase 4).
 */
class RunRegistry {
    private val active = ConcurrentHashMap.newKeySet<String>()

    // TODO(review) KL-01: single-process, single-run-at-a-time agent — a second
    // concurrent run is rejected (409); concurrent/distributed execution is a
    // pre-declared out-of-scope item (PLAN §12).
    fun start(plannedRunId: String): Boolean = active.add(plannedRunId)

    /** The single-run gate (KL-01): the run id, or null when ANY run is already
     *  active (the 409 path). Always [finish] the id in a finally. */
    fun tryBegin(): String? =
        if (active.isEmpty()) {
            RunAudit.newRunId().also { active.add(it) }
        } else {
            null
        }

    fun finish(runId: String) {
        active.remove(runId)
    }

    fun activeRunIds(): Set<String> = active.toSet()
}
