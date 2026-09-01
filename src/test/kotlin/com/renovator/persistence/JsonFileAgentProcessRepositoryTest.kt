package com.renovator.persistence

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessOptions
import com.renovator.agent.states.Applying
import com.renovator.domain.ChangeScope
import com.renovator.domain.DependencyTarget
import com.renovator.domain.PlanStep
import com.renovator.domain.RepoModel
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import com.renovator.domain.VersionChange
import com.renovator.validation.ValidatedPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.Path

/**
 * The file-backed repository's contract (PLAN Task 4.5 / C-5): save persists the
 * typed frame, the snapshot round-trips EXACT domain types (the executor's proof
 * digest rebinds from the same payload bytes — the enforcement boundary survives
 * the kill), and ephemeral processes are never written to disk.
 */
class JsonFileAgentProcessRepositoryTest {
    private val runId = "unit-repo"
    private val repository = JsonFileAgentProcessRepository(Path.of("var/runs"))

    private fun goal() = UpgradeGoal(targets = listOf(DependencyTarget("org.apache.commons", "commons-lang3", "2.6", "3.14.0")))

    private fun applying(): Applying {
        val plan =
            UpgradePlan(
                steps =
                    listOf(
                        PlanStep.VersionStep(
                            VersionChange("org.apache.commons", "commons-lang3", "2.6", "3.14.0", ChangeScope.DIRECT),
                        ),
                    ),
                rationale = "migrate",
            )
        return Applying(
            goal = goal(),
            runRequest = RunRequest(repoPath = Path.of("fixtures/fixture-api-removal"), goal = goal()),
            repoModel = RepoModel(emptyList(), emptyList(), "17"),
            validatedPlan = ValidatedPlan.create(plan, listOf("L1:plan-paths", "L2:plan-diff", "L3:versions")),
        )
    }

    private fun process(
        objects: List<Any>,
        ephemeral: Boolean,
    ): AgentProcess {
        val p = Mockito.mock(AgentProcess::class.java)
        Mockito.`when`(p.objects).thenReturn(objects)
        Mockito.`when`(p.id).thenReturn(runId)
        val options = Mockito.mock(ProcessOptions::class.java)
        Mockito.`when`(options.ephemeral).thenReturn(ephemeral)
        Mockito.`when`(p.processOptions).thenReturn(options)
        return p
    }

    @Test
    fun `saved process state restores typed blackboard objects`() {
        Files.deleteIfExists(Path.of("var/runs/$runId/process.json"))
        val snap = applying()
        repository.save(process(listOf(snap), ephemeral = false))

        val loaded = repository.load(runId)
        assertNotNull(loaded, "the snapshot must be on disk")
        assertEquals("Applying", loaded!!.frame)
        assertEquals(goal(), loaded.goal, "the goal is a typed object, not JSON soup")
        assertEquals(
            Path.of("fixtures/fixture-api-removal").toAbsolutePath(),
            loaded.runRequest.repoPath.toAbsolutePath(),
            "the repo path survives (serialized as an absolute path; same target)",
        )
        assertEquals(goal(), loaded.goal)
        assertEquals(goal(), loaded.runRequest.goal)
        assertEquals("migrate", loaded.planRationale)
        assertEquals(1, loaded.planSteps.size)
        val step = loaded.planSteps.single()
        assertTrue(step is PlanStep.VersionStep, "the step type survives the round-trip: $step")
        assertEquals("3.14.0", (step as PlanStep.VersionStep).change.toVersion, "typed change payload")

        // The execution boundary rebinds: same payload bytes -> same sha256; the
        // executor's verifyProof accepts the restored plan.
        val rebound = loaded.validatedPlan()
        assertEquals(snap.validatedPlan.proof.contentDigestSha256, rebound.proof.contentDigestSha256, "digest rebinds")
        assertEquals(
            listOf("L1:plan-paths", "L2:plan-diff", "L3:versions"),
            rebound.proof.checkNames,
            "the proof's check names survive",
        )
    }

    @Test
    fun `ephemeral processes are never persisted`() {
        Files.deleteIfExists(Path.of("var/runs/$runId/process.json"))
        repository.save(process(listOf(applying()), ephemeral = true))
        assertNull(repository.load(runId), "C-5: ephemeral processes never hit the disk")
    }

    @Test
    fun `a non-Applying frame cannot be snapshotted`() {
        val repo = JsonFileAgentProcessRepository(Path.of("var/runs"))
        Files.deleteIfExists(Path.of("var/runs/$runId/process.json"))
        repository.save(
            process(
                listOf(
                    com.renovator.agent.states.Blocked(
                        goal(),
                        RunRequest(Path.of("fixtures/fixture-clean"), goal()),
                        com.renovator.domain.UpgradeBlocker(
                            "x",
                            listOf(com.renovator.domain.AttemptRecord("r", "t", emptyList(), emptyList())),
                            "q?",
                        ),
                    ),
                ),
                ephemeral = false,
            ),
        )
        assertNull(repository.load(runId), "only the resumable frame is persisted (KL-08)")
    }
}
