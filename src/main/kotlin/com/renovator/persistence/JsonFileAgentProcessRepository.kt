package com.renovator.persistence

import com.embabel.agent.core.AbstractAgentProcessRepository
import com.embabel.agent.core.AgentProcess
import com.renovator.agent.states.Applying
import com.renovator.config.JacksonConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * File-backed process repository (PLAN Task 4.5 / D10): every persistable
 * snapshot lands as `var/runs/{runId}/process.json` (Jackson, typed). `save` and
 * `update` both write the snapshot of the process's live frame; ephemeral
 * processes are never written (the SPI contract, C-5). `findById` restores the
 * snapshot (not a live process — re-seeding is RunService's job, see the
 * [RunSnapshot] KDoc for the KL-08 boundary).
 */
class JsonFileAgentProcessRepository(
    private val runsDir: Path = Path.of("var/runs"),
) : AbstractAgentProcessRepository() {
    override fun doSave(process: AgentProcess): AgentProcess {
        persist(process)
        return process
    }

    override fun doUpdate(process: AgentProcess) {
        persist(process)
    }

    override fun findById(id: String): AgentProcess =
        throw UnsupportedOperationException(
            "findById restores snapshots, not live processes: use load(id) + RunService.resume",
        )

    override fun findByParentId(parentId: String): List<AgentProcess> = emptyList()

    override fun delete(process: AgentProcess) {
        Files.deleteIfExists(file(process.id))
    }

    /** Snapshot the process's typed frame. Only processes with a persisted
     *  payload (an Applying frame) are file-worthy; the [ApplySnapshot.of]
     *  returns null for anything else, keeping the file contract honest. */
    private fun persist(process: AgentProcess) {
        if (process.processOptions.ephemeral) {
            return // C-5: ephemeral processes are never persisted.
        }
        ApplySnapshot.of(process)?.let { write(it) }
    }

    fun load(runId: String): RunSnapshot? {
        val path = file(runId)
        if (!Files.exists(path)) {
            return null
        }
        return JacksonConfig().proposalObjectMapper().readValue(path.toFile(), RunSnapshot::class.java)
    }

    fun write(snapshot: RunSnapshot) {
        val dir = runsDir.resolve(snapshot.runId)
        Files.createDirectories(dir)
        Files.writeString(file(snapshot.runId), JacksonConfig().proposalObjectMapper().writeValueAsString(snapshot))
    }

    private fun file(runId: String): Path = runsDir.resolve(runId).resolve("process.json")
}

/** Builds a [RunSnapshot] from a live process's blackboard (typed reads only). */
object ApplySnapshot {
    /** The persisted snapshot of the machine's CURRENT frame. The payload is the
     *  applied plan (+ pending patch) — the same continuation payload for every
     *  frame from Applying onward (KL-08); frames before the first apply have no
     *  payload and are not persisted (Analyzing/Planning/Blocked/Done). */
    fun of(process: AgentProcess): RunSnapshot? {
        val frame =
            process.objects
                .filterIsInstance<com.renovator.agent.states.UpgradeStage>()
                .lastOrNull() ?: return null
        val goal: com.renovator.domain.UpgradeGoal
        val runRequest: com.renovator.domain.RunRequest
        val repoModel: com.renovator.domain.RepoModel
        val validatedPlan: com.renovator.validation.ValidatedPlan
        val pendingPatch: com.renovator.domain.CodePatch?
        when (frame) {
            is Applying -> {
                goal = frame.goal
                runRequest = frame.runRequest
                repoModel = frame.repoModel
                validatedPlan = frame.validatedPlan
                pendingPatch = frame.pendingPatch?.patch
            }

            is com.renovator.agent.states.Verifying -> {
                goal = frame.goal
                runRequest = frame.runRequest
                repoModel = frame.repoModel
                validatedPlan = frame.validatedPlan
                pendingPatch = null
            }

            is com.renovator.agent.states.Repairing -> {
                goal = frame.goal
                runRequest = frame.runRequest
                repoModel = frame.repoModel
                validatedPlan = frame.validatedPlan
                pendingPatch = null
            }

            else -> {
                return null
            }
        }
        return RunSnapshot(
            runId = com.renovator.audit.RunAudit.runId ?: process.id,
            frame = frame::class.simpleName ?: "unknown",
            goal = goal,
            runRequest = runRequest,
            repoModel = repoModel,
            planSteps = validatedPlan.plan.steps,
            planRationale = validatedPlan.plan.rationale,
            proofCheckNames = validatedPlan.proof.checkNames,
            pendingPatch = pendingPatch,
        )
    }
}
