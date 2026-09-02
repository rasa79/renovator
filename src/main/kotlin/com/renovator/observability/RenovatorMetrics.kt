package com.renovator.observability

import com.renovator.audit.TrajectoryEvent
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// LEARN[019] Register metrics eagerly so the scrape's meter-name set is STABLE,
// not state-dependent
// Why this way: a Micrometer counter only appears in the Prometheus scrape the
//   moment it is first created, and RenovatorMetrics created its counters lazily
//   inside observe() — only when the matching trajectory event fired. So the
//   scrape's exposed name-set depended on WHICH run had just happened. A
//   fixture-clean run never enters Repairing, so it never created
//   renovator.replans.total → the endpoint exposed plans-attempted but not
//   replans, and PrometheusMetricsIT (which asserts all four names after a
//   fixture-clean run) failed in isolation. It only "passed" in a full suite
//   when a PRIOR test (some fixture that did enter Repairing) had already
//   registered that counter in the shared registry — i.e. it was order- and
//   state-dependent, exactly the kind of non-repeatable green that a fresh-clone
//   reproduction must catch. The fix: register every meter (and the timer) once
//   at attach() time. Micrometer treats the (un)tagged base and any tagged
//   variant as distinct meters, so pre-registering the untagged bases and letting
//   observe() add tagged variants coexists cleanly; counts start at 0. Now the
//   Prometheus endpoint always exposes all four names + the timer, regardless of
//   which events any given run fires — a stable name-set for dashboards/alerts.
// Good sides: deterministic, order-independent scrapes; the meter names are a
//   stable contract a monitor can rely on; the assertion in PrometheusMetricsIT
//   is true in isolation and in any suite order.
// Drawbacks: a meter that never increments still appears at 0.0 — that is the
//   point (stable labels), and the value is honest (nothing was observed).
// Concept: register-up-front, increment-on-event — never create-on-event.
// See also: PLAN Task 6.3 ("the prometheus endpoint exposes all four meter names"),
//   PrometheusMetricsIT, MetricsIT

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
            // Eagerly register the four meters + timer so the Prometheus scrape
            // always exposes them, even when a run never fires the corresponding
            // event (a fixture-clean run never enters Repairing, so a lazy
            // registration would leave renovator_replans_total absent from the
            // scrape — a test-order-dependent gap, LEARN[019]). Micrometer meters
            // are stable by id: pre-registering their (un)tagged base and letting
            // observe() add tagged variants coexists cleanly; counts start at 0.
            registry.counter("renovator.plans.attempted")
            registry.counter("renovator.replans.total")
            registry.counter("renovator.validation.rejections")
            registry.counter("renovator.escalations.total")
            registry.timer("renovator.time.to.green")
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
