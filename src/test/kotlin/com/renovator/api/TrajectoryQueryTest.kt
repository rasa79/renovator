package com.renovator.api

import com.renovator.audit.TrajectoryStore
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Trajectory query (PLAN Task 6.4): the existing endpoint now filters by event
 * type AND by stage; every line a query can see is valid JSON carrying a
 * sequence number (property-flavored — runs over all trajectory files under
 * var/runs/ produced by the test suite).
 */
class TrajectoryQueryTest {

    private fun freshTrajectory(): String {
        val runId = "query-it"
        Path.of("var/runs/$runId/trajectory.jsonl")
            .toFile()
            .parentFile
            .mkdirs()
        Files.deleteIfExists(Path.of("var/runs/$runId/trajectory.jsonl"))
        com.renovator.audit.RunAudit.clear()
        com.renovator.audit.RunAudit.runId = runId
        com.renovator.audit.RunAudit.emit(com.renovator.audit.TrajectoryEvent.StageEntered("Analyzing"))
        com.renovator.audit.RunAudit.emit(com.renovator.audit.TrajectoryEvent.PlanAttempted(rationale = "bump", stepCount = 1))
        com.renovator.audit.RunAudit.emit(com.renovator.audit.TrajectoryEvent.ValidationOutcome(checkName = "L3:version-exists", accepted = false, reason = "nope"))
        com.renovator.audit.RunAudit.emit(com.renovator.audit.TrajectoryEvent.StageEntered("Planning"))
        com.renovator.audit.RunAudit.emit(com.renovator.audit.TrajectoryEvent.Completed(terminal = "UpgradeComplete"))
        return runId
    }

    @Test
    fun `filters by event type`() {
        val runId = freshTrajectory()
        val store = TrajectoryStore()
        val validation = store.read(runId).filter { it.contains("\"eventType\":\"ValidationOutcome\"") }
        assertTrue(validation.size == 1, "one validation event: $validation")
        val plans = store.read(runId).filter { it.contains("\"eventType\":\"PlanAttempted\"") }
        assertTrue(plans.size == 1, "one plan attempt: $plans")
        // The endpoint-level filter (RunService.trajectory with type) does the same.
        val svc = RunService(com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform())
        val byType = svc.trajectory(runId, "ValidationOutcome")
        assertTrue(byType.size == 1 && byType.single().contains("L3:version-exists"), "type filter: $byType")
    }

    @Test
    fun `filters by stage`() {
        val runId = freshTrajectory()
        val svc = RunService(com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform())
        val analyzing = svc.trajectory(runId, stage = "Analyzing")
        assertTrue(analyzing.size == 1 && analyzing.single().contains("\"stage\":\"Analyzing\""), "stage filter: $analyzing")
        val combined = svc.trajectory(runId, type = "StageEntered", stage = "Planning")
        assertTrue(combined.size == 1 && combined.single().contains("\"stage\":\"Planning\""), "type+stage filter: $combined")
    }

    @Test
    fun `every trajectory line is valid JSON with a sequence number`() {
        // Property-flavored, over every trajectory file the suite produces.
        val roots = listOf(Path.of("var/runs"))
        var checked = 0
        val mapper = com.renovator.config.JacksonConfig().proposalObjectMapper()
        for (root in roots) {
            if (!Files.exists(root)) {
                continue
            }
            Files.walk(root).use { walk ->
                walk
                    .filter { it.toString().endsWith(".jsonl") }
                    .forEach { file ->
                        Files.readAllLines(file).forEach { line ->
                            if (line.isBlank()) {
                                return@forEach
                            }
                            val node = mapper.readTree(line)
                            assertTrue(node.has("seq"), "line has seq: ${line.take(120)}")
                            assertTrue(node.has("event"), "line has event: ${line.take(120)}")
                            assertTrue(node.get("seq").isNumber, "seq is a number: ${line.take(80)}")
                            checked += 1
                        }
                    }
            }
        }
        assertTrue(checked >= 1, "checked at least one trajectory line (suite-produced): $checked")
    }
}
