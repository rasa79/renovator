package com.renovator.api

import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessOptions
import com.renovator.agent.states.Applying
import com.renovator.audit.RunAudit
import com.renovator.audit.TrajectoryEvent
import com.renovator.config.ProcessOptionsFactory
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeGoal
import com.renovator.persistence.JsonFileAgentProcessRepository
import com.renovator.persistence.RunSnapshot

/**
 * Run orchestration (PLAN Task 4.5 skeleton; the REST layer in Phase 5 exposes
 * it): submit runs and RESUME them. The resume is the pre-declared fallback
 * (KL-08): the snapshot re-seeds a fresh process whose blackboard carries the
 * saved Applying frame — the entry action is closed by the `freshRun` condition
 * (a state is already on the board), so the continuation picks up from
 * `applyValidatedChanges` with NO repeated analysis (D10, KillResumeIT).
 */
class RunService(
    private val platform: AgentPlatform,
    private val agent: Agent,
    private val repository: JsonFileAgentProcessRepository = JsonFileAgentProcessRepository(),
    private val optionsFactory: ProcessOptionsFactory = ProcessOptionsFactory(),
) {
    fun submit(
        goal: UpgradeGoal,
        runRequest: RunRequest,
        plannerType: com.embabel.agent.api.common.PlannerType? = com.embabel.agent.api.common.PlannerType.GOAP,
    ): AgentProcess {
        val options = optionsFactory.processOptions(plannerType)
        val process = platform.createAgentProcess(agent, options, mapOf("goal" to goal, "runRequest" to runRequest))
        process.run()
        repository.update(process)
        return process
    }

    /** Continue a killed run: snapshot -> re-seed -> run to completion. */
    fun resume(
        runId: String,
        plannerType: com.embabel.agent.api.common.PlannerType? = com.embabel.agent.api.common.PlannerType.GOAP,
    ): AgentProcess {
        val snapshot =
            repository.load(runId)
                ?: error("no snapshot for run $runId (nothing was persisted for it)")
        // KL-08: the resume re-enters AT THE LAST APPLY — the re-seed is the
        // validated plan payload (+ pending patch), regardless of the frame the
        // snapshot was taken in. Before the first apply there is no payload;
        // such a snapshot is rejected rather than silently restarted.
        require(snapshot.planSteps.isNotEmpty()) {
            "run $runId has no applied payload yet (frame '${snapshot.frame}'); only runs past the first apply are resumable (KL-08)"
        }
        val applying =
            Applying(
                goal = snapshot.goal,
                runRequest = snapshot.runRequest,
                repoModel = snapshot.repoModel,
                validatedPlan = snapshot.validatedPlan(),
                pendingPatch =
                    snapshot.pendingPatch?.let {
                        com.renovator.agent.actions.ValidatePatchAction
                            .validate(it, snapshot.runRequest)
                            .let { outcome ->
                                when (outcome) {
                                    is com.renovator.agent.actions.ValidatePatchAction.Outcome.Accepted -> {
                                        outcome.patch
                                    }

                                    is com.renovator.agent.actions.ValidatePatchAction.Outcome.Rejected -> {
                                        error("pending patch no longer validates after resume: ${outcome.rejection.reason}")
                                    }
                                }
                            }
                    },
            )
        val options = optionsFactory.processOptions(plannerType)
        val process = platform.createAgentProcess(agent, options, mapOf("applying" to applying))
        RunAudit.runId = runId
        RunAudit.emit(
            TrajectoryEvent.Resumed(
                reason = "kill-and-resume: JVM died during frame ${snapshot.frame}",
                frame = snapshot.frame,
            ),
        )
        process.run()
        repository.update(process)
        return process
    }
}
