package com.renovator.audit

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lightweight run trace: every palette action records its name in order. This is
 * the planner-ordering evidence (Task 3.3 PlannerOrderingIT) and the seed of the
 * full trajectory store (Task 3.4 replaces it by the typed JSONL TrajectoryStore).
 */
object AgentTrace {
    private val entries = CopyOnWriteArrayList<String>()

    fun record(name: String) {
        entries += name
    }

    fun snapshot(): List<String> = entries.toList()

    fun clear() {
        entries.clear()
    }
}
