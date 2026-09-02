package com.renovator.observability

import com.renovator.eval.EvalHarness
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Path

/**
 * Metrics (PLAN Task 6.3): the trajectory events are the only metric source —
 * after a fixture-clean run plans-attempted is 1 and time-to-green is recorded;
 * after a no-path run validation rejections carry the L3 tag and escalations is
 * 1; the prometheus endpoint exposes all four meter names.
 */
class MetricsIT {
    private var previousRegistry: io.micrometer.core.instrument.MeterRegistry? = null

    private fun useRegistry(): SimpleMeterRegistry {
        previousRegistry = RenovatorMetrics.registry
        return SimpleMeterRegistry().also { RenovatorMetrics.attach(it) }
    }

    @AfterEach
    fun restore() {
        RenovatorMetrics.registry = previousRegistry
    }

    @Test
    fun `after a fixture-clean run, plans-attempted is 1 and time-to-green is recorded`() {
        val reg = useRegistry()
        val result = EvalHarness.runFixture("fixture-clean")
        assertEquals("UpgradeComplete", result.terminal, "sanity: the run completed")

        val plans = reg.counter("renovator.plans.attempted").count()
        assertEquals(1.0, plans, "one plan attempt: $plans")
        val timer = reg.timer("renovator.time.to.green")
        assertTrue(timer.count() == 1L, "the run's time-to-green recorded: $timer")
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) > 0, "a positive duration: $timer")
    }

    @Test
    fun `after a no-path run, validation rejections carry the L3 tag and escalations is 1`() {
        val reg = useRegistry()
        val result = EvalHarness.runFixture("fixture-no-path")
        assertEquals("UpgradeBlocker", result.terminal, "sanity: the blocker")

        val rejections = reg.counter("renovator.validation.rejections", "check", "L3:version-exists").count()
        assertEquals(5.0, rejections, "five L3 rejections: $rejections")
        val escalations = reg.counter("renovator.escalations.total").count()
        assertEquals(1.0, escalations, "one escalation: $escalations")
        val replans = reg.counter("renovator.replans.total").count()
        assertEquals(0.0, replans, "no repair replans on no-path: $replans")
    }
}

/** The prometheus scrape (PLAN Task 6.3, the endpoint demo): all four meters. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrometheusMetricsIT {
    @LocalServerPort
    var port: Int = 0

    @Test
    fun `prometheus endpoint exposes all four meter names after a fixture run`() {
        // Drive a run with the app's real registry via the harness (the app is up).
        val runId = EvalHarness.runFixture("fixture-clean")
        assertEquals("UpgradeComplete", runId.terminal)

        val body =
            RestClient.create()
                .get()
                .uri("http://localhost:$port/actuator/prometheus")
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        for (name in listOf(
            "renovator_plans_attempted_total",
            "renovator_replans_total",
            "renovator_validation_rejections_total",
            "renovator_escalations_total",
        )) {
            assertTrue(body.contains(name), "missing $name in scrape:\n${body.take(400)}")
        }
        assertTrue(body.contains("renovator_time_to_green_seconds") || body.contains("renovator_time_to_green"), "the timer in scrape: ${body.take(400)}")
        println("PROMETHEUS SCRAPE sample: ${body.lines().filter { it.startsWith("renovator_") }.take(6)}")
    }
}
