package com.renovator.audit

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * The application event bus (PLAN Task 5.2): RunAudit publishes every typed
 * event here AFTER the trajectory line is appended, and SSE subscribers tail
 * live events from it. The trajectory FILE stays the source of truth (replay +
 * interrupted-write safety); the bus is the live tail only, so a subscriber
 * that misses events can always re-read the file.
 */
object TrajectoryBus {
    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<PublishedEvent>>>()

    data class PublishedEvent(
        val runId: String,
        val seq: Long,
        val line: String,
    )

    fun subscribe(
        runId: String,
        consumer: Consumer<PublishedEvent>,
    ): AutoCloseable {
        val list = subscribers.computeIfAbsent(runId) { CopyOnWriteArrayList() }
        list.add(consumer)
        return AutoCloseable { list.remove(consumer) }
    }

    fun publish(event: PublishedEvent) {
        subscribers[event.runId]?.forEach { it.accept(event) }
    }
}
