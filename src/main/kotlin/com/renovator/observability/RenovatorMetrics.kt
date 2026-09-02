package com.renovator.observability

import com.renovator.audit.TrajectoryEvent
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Metrics (PLAN Task 6.3): the trajectory events are the only honest source —
 * every plan attempt, replan, validation rejection and escalation is counted
 * where it happens (the RunAudit emit hook), never by intercepting the LLM.
 * The timer measures Analyzing -> terminal per run. A NOOP when no registry is
 * attached (the in-process ITs run without Spring; MetricsIT attaches a
 * SimpleMeterRegistry and asserts against it; the app attaches the real one).
 */
@Component
class RenovatorMetrics(
    registry: MeterRegistry,
) {
    init {
        attach(registry)
    }

    companion object {
        @Volatile
        var registry: MeterRegistry? = null

        private val startOfRun = ConcurrentHashMap<String, Long>()

        fun attach(registry: MeterRegistry) {
            this.registry = registry
        }

        private fun counter(
            name: String,
            tag: String? = null,
        ): Counter? {
            val reg = registry ?: return null
            return if (tag == null) reg.counter(name) else reg.counter(name, "check", tag)
        }

        /** One trajectory event -> one metric increment (or none). */
        fun observe(
            event: TrajectoryEvent,
            runId: String?,
        ) {
            val id = runId ?: return
            when (event) {
                is TrajectoryEvent.StageEntered -> {
                    if (event.stage == "Analyzing") {
                        startOfRun[id] = System.nanoTime()
                    }
                    if (event.stage == "Repairing") {
                        counter("renovator.replans.total")?.increment()
                    }
                }

                is TrajectoryEvent.PlanAttempted -> {
                    counter("renovator.plans.attempted")?.increment()
                }

                is TrajectoryEvent.ValidationOutcome -> {
                    if (!event.accepted) {
                        counter("renovator.validation.rejections", event.checkName)?.increment()
                    }
                }

                is TrajectoryEvent.Escalated -> {
                    counter("renovator.escalations.total")?.increment()
                }

                is TrajectoryEvent.Completed -> {
                    val started = startOfRun.remove(id)
                    val reg = registry
                    if (started != null && reg != null) {
                        reg.timer("renovator.time.to.green").record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
                    }
                }

                else -> {
                    Unit
                }
            }
        }
    }
}
