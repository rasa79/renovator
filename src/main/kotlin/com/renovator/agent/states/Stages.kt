package com.renovator.agent.states

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.State
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.ReplanRequestedException
import com.renovator.agent.actions.AnalyzeRepositoryAction
import com.renovator.agent.actions.ApplyValidatedChangesAction
import com.renovator.agent.actions.DryRunCompileAction
import com.renovator.agent.actions.FinalizeUpgradeAction
import com.renovator.agent.actions.LlmActions
import com.renovator.agent.actions.LlmOutcome
import com.renovator.agent.actions.RequestHumanDecisionAction
import com.renovator.agent.actions.RunBuildAction
import com.renovator.agent.actions.ValidatePatchAction
import com.renovator.agent.actions.ValidatePlanAction
import com.renovator.audit.AgentTrace
import com.renovator.audit.RunAudit
import com.renovator.audit.TrajectoryEvent
import com.renovator.domain.BuildDiagnosis
import com.renovator.domain.BuildResult
import com.renovator.domain.CodePatch
import com.renovator.domain.HumanDecision
import com.renovator.domain.RepoModel
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeBlocker
import com.renovator.domain.UpgradeComplete
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import com.renovator.domain.WorkspaceVerdict
import com.renovator.execution.WorkspaceSnapshot
import com.renovator.validation.CompileErrorParser
import com.renovator.validation.ValidatedPatch
import com.renovator.validation.ValidatedPlan

// LEARN[012] @State loops: state scoping and why clearBlackboard = true exists
// Why this way: a loop action that returns a state type the machine has already
//   visited hits the blackboard's "output type already exists" rule — the planner
//   would skip the action because it believes the effect already happened. That
//   is exactly right for a linear flow and exactly wrong for a loop. clearBlackboard
//   = true is the escape: it wipes the blackboard (including hasRun flags) so the
//   machine can legitimately revisit a state type. The cost is that ALL loop data
//   must ride the state instances — the framework's own docs say "pass all
//   necessary data through state record fields". This is why UpgradeStage members
//   carry goal/runRequest/repoModel/plan/attempts instead of relying on blackboard
//   keys: after a clear, the blackboard is empty and the state is the memory.
// Good sides: transitions are inspectable objects (trace them; the trajectory gets
//   real StageEntered events); scoping makes the planner's reachable set obvious
//   (only the current state's actions are plannable — StateScopingTest proves it);
//   the loop-count/attempts budget is data, not a global.
// Drawbacks: data duplication between states (each transition re-carries the whole
//   context); clearBlackboard also wipes hasRun, so an action in a loop can re-run
//   by design — the attempts ledger is what bounds it (LEARN[014]); and a state
//   with no action is a dead end, so every state must have an exit — a design
//   property that compilation cannot check, only the state tests can.
// Concept: think of states as frames in a protocol stack — every frame carries the
//   payload it needs, and popping/clearing the blackboard is a stack reset. The
//   planner routes by frame, data survives only in frames, and the loop exit
//   condition is data (attempt count), not structure.
// See also: PLAN §5, PLAN §2 C-2, LEARN[009] (planner), LEARN[014] (attempt budget)

/** The lifecycle: @State on the parent; children are top-level data classes. */
@State
sealed interface UpgradeStage

/** Entry state: goal + run request ride the state from the very first frame. */
data class Analyzing(
    val goal: UpgradeGoal,
    val runRequest: RunRequest,
) : UpgradeStage {
    @Action(cost = 0.05, description = "Analyze the target repository (pom facts)")
    fun analyzeRepository(): Planning {
        AgentTrace.record("analyzeRepository")
        return Planning(
            goal = goal,
            runRequest = runRequest,
            repoModel = AnalyzeRepositoryAction.analyze(runRequest),
        ).also { RunAudit.emit(TrajectoryEvent.StageEntered("Planning")) }
    }
}

data class Planning(
    val goal: UpgradeGoal,
    val runRequest: RunRequest,
    val repoModel: RepoModel,
    /** Set when this Planning frame was reached through a repair replan (Task 4.3):
     *  the failed build's diagnosis rides the state and informs the new proposal. */
    val lastFailure: BuildDiagnosis? = null,
) : UpgradeStage {
    /** LLM proposal: returns the plan object (no transition — stays in Planning).
     *  On a replan the failure diagnosis is part of the prompt, so the second
     *  proposal has the signal the first one lacked (PLAN §6.1 step 8). */
    @Action(cost = 0.30, description = "Propose an upgrade plan (LLM, typed binding)")
    fun proposeUpgradePlan(context: OperationContext): UpgradePlan {
        AgentTrace.record("proposeUpgradePlan")
        return when (
            val outcome =
                com.renovator.agent.llm.LlmChannel.actions
                    .proposePlan(context, repoModel, goal, lastFailure)
        ) {
            is LlmOutcome.Accepted -> {
                RunAudit.emit(
                    TrajectoryEvent.PlanAttempted(
                        rationale = outcome.value.rationale,
                        stepCount = outcome.value.steps.size,
                    ),
                )
                RunAudit.emit(
                    TrajectoryEvent.LlmCall(
                        action = "proposePlan",
                        attempts = outcome.attempts.size,
                        rejected = false,
                        reason = "",
                    ),
                )
                outcome.value
            }

            is LlmOutcome.Rejected -> {
                AgentTrace.record("proposeUpgradePlan:REJECTED:${outcome.rejection.checkName}:${outcome.rejection.reason}")
                RunAudit.emit(
                    TrajectoryEvent.LlmCall(
                        action = "proposePlan",
                        attempts = outcome.attempts.size,
                        rejected = true,
                        reason = outcome.rejection.reason,
                    ),
                )
                throw ReplanRequestedException(outcome.rejection.reason)
            }
        }
    }

    /** Deterministic L1-L3 validation; transitions forward on accept. */
    @Action(cost = 0.05, description = "Validate a proposed plan against L1-L3")
    fun validatePlan(plan: UpgradePlan): Applying {
        AgentTrace.record("validatePlan")
        return when (
            val outcome =
                ValidatePlanAction.validate(
                    plan,
                    runRequest.repoPath
                        .resolve("pom.xml")
                        .toFile()
                        .readText(),
                )
        ) {
            is ValidatePlanAction.Outcome.Accepted -> {
                Applying(goal, runRequest, repoModel, outcome.plan).also {
                    RunAudit.emit(
                        TrajectoryEvent.ValidationOutcome(
                            checkName =
                                outcome.plan.proof.checkNames
                                    .joinToString(","),
                            accepted = true,
                            reason = "",
                        ),
                    )
                    RunAudit.emit(TrajectoryEvent.StageEntered("Applying"))
                }
            }

            is ValidatePlanAction.Outcome.Rejected -> {
                AgentTrace.record("validatePlan:REJECTED:${outcome.rejection.checkName}:${outcome.rejection.reason}")
                RunAudit.emit(
                    TrajectoryEvent.ValidationOutcome(
                        checkName = outcome.rejection.checkName,
                        accepted = false,
                        reason = outcome.rejection.reason,
                    ),
                )
                throw ReplanRequestedException(outcome.rejection.reason)
            }
        }
    }
}

data class Applying(
    val goal: UpgradeGoal,
    val runRequest: RunRequest,
    val repoModel: RepoModel,
    val validatedPlan: ValidatedPlan,
    val pendingPatch: ValidatedPatch? = null,
) : UpgradeStage {
    @Action(cost = 0.10, description = "Apply validated changes to a pristine workspace copy")
    fun applyValidatedChanges(): Verifying {
        AgentTrace.record("applyValidatedChanges")
        // The repair bundle: the validated plan (pom) plus any validated patch (code).
        // ApplyValidatedChangesAction stages the plan on a pristine copy; the patch is
        // applied to the SAME copy by the executor's patch path. The source tree is
        // never mutated (D7) — the copy is.
        val snapshot = ApplyValidatedChangesAction.apply(validatedPlan, runRequest, pendingPatch)
        return Verifying(goal, runRequest, repoModel, validatedPlan, snapshot).also {
            RunAudit.emit(TrajectoryEvent.StageEntered("Verifying"))
        }
    }
}

data class Verifying(
    val goal: UpgradeGoal,
    val runRequest: RunRequest,
    val repoModel: RepoModel,
    val validatedPlan: ValidatedPlan,
    val snapshot: WorkspaceSnapshot,
) : UpgradeStage {
    /** The judge's verdict: green -> Done; red -> Repairing. The loop transition
     *  (Verifying <-> Repairing) requires clearBlackboard = true. */
    @Action(cost = 0.60, description = "Run the sandboxed build and tests", clearBlackboard = true)
    fun runBuild(): UpgradeStage {
        AgentTrace.record("runBuild")
        val verdict: WorkspaceVerdict = RunBuildAction.runBuild(snapshot.ref)
        RunAudit.emit(
            TrajectoryEvent.BuildObserved(
                success = verdict.build.success,
                failedGoals = verdict.build.failedGoals,
                durationMs = verdict.build.durationMs,
            ),
        )
        return if (verdict.build.success && verdict.tests.failed == 0) {
            Done(goal, runRequest, validatedPlan, verdict).also {
                RunAudit.emit(TrajectoryEvent.StageEntered("Done"))
            }
        } else {
            Repairing(goal, runRequest, repoModel, validatedPlan, snapshot, verdict, emptyList()).also {
                RunAudit.emit(TrajectoryEvent.StageEntered("Repairing"))
            }
        }
    }

    @Action(cost = 0.80, description = "Dry-run compile in the sandbox (expensive opinion)", pre = ["commitCandidacyArmed"])
    fun dryRunCompile(): com.renovator.domain.CompileCheckResult {
        AgentTrace.record("dryRunCompile")
        return DryRunCompileAction.dryRunPlan(validatedPlan, runRequest)
    }
}

data class Repairing(
    val goal: UpgradeGoal,
    val runRequest: RunRequest,
    val repoModel: RepoModel,
    val validatedPlan: ValidatedPlan,
    val snapshot: WorkspaceSnapshot,
    val failedVerdict: WorkspaceVerdict,
    val attempts: List<com.renovator.domain.AttemptRecord>,
) : UpgradeStage {
    @Action(cost = 0.30, description = "Diagnose a failed build (LLM, typed binding)")
    fun diagnoseFailure(context: OperationContext): BuildDiagnosis {
        AgentTrace.record("diagnoseFailure")
        return when (
            val outcome =
                com.renovator.agent.llm.LlmChannel.actions
                    .diagnoseFailure(context, failedVerdict.build)
        ) {
            is LlmOutcome.Accepted -> {
                RunAudit.emit(
                    TrajectoryEvent.ProposalReceived(
                        kind = "BuildDiagnosis",
                        summary = outcome.value.rootCauses.joinToString("; ") { it.symbolOrArtifact },
                    ),
                )
                RunAudit.emit(
                    TrajectoryEvent.LlmCall(
                        action = "diagnoseFailure",
                        attempts = outcome.attempts.size,
                        rejected = false,
                        reason = "",
                    ),
                )
                outcome.value
            }

            is LlmOutcome.Rejected -> {
                AgentTrace.record("diagnoseFailure:REJECTED:${outcome.rejection.checkName}:${outcome.rejection.reason}")
                RunAudit.emit(
                    TrajectoryEvent.LlmCall(
                        action = "diagnoseFailure",
                        attempts = outcome.attempts.size,
                        rejected = true,
                        reason = outcome.rejection.reason,
                    ),
                )
                throw ReplanRequestedException(outcome.rejection.reason)
            }
        }
    }

    @Action(cost = 0.30, description = "Propose a code patch (LLM, typed binding)", pre = ["diagnosisSuggestsPatch"])
    fun proposePatch(
        diagnosis: BuildDiagnosis,
        context: OperationContext,
    ): CodePatch {
        AgentTrace.record("proposePatch")
        // The patch targets the workspace COPY the failed build was run on — its
        // current content is the only truthful context for the repair diff (D7):
        // the sandbox mounts the copy at /work, so the javac diagnostic's absolute
        // path is relativized against the mount point first.
        val errors = CompileErrorParser.parse(failedVerdict.build.log.head + "\n" + failedVerdict.build.log.tail)
        val failing =
            errors.firstOrNull()?.filePath?.removePrefix(com.renovator.execution.DockerSandboxRunner.WORK_MOUNT + "/")
                ?: throw ReplanRequestedException("no javac diagnostic in the failed build log to name the patch target")
        val fileContent =
            snapshot.ref.path
                .resolve(failing)
                .toFile()
                .readText()
        return when (
            val outcome =
                com.renovator.agent.llm.LlmChannel.actions
                    .proposePatch(context, diagnosis, fileContent)
        ) {
            is LlmOutcome.Accepted -> {
                RunAudit.emit(
                    TrajectoryEvent.ProposalReceived(
                        kind = "CodePatch",
                        summary = outcome.value.justification,
                    ),
                )
                RunAudit.emit(
                    TrajectoryEvent.LlmCall(
                        action = "proposePatch",
                        attempts = outcome.attempts.size,
                        rejected = false,
                        reason = "",
                    ),
                )
                outcome.value
            }

            is LlmOutcome.Rejected -> {
                AgentTrace.record("proposePatch:REJECTED:${outcome.rejection.checkName}:${outcome.rejection.reason}")
                RunAudit.emit(
                    TrajectoryEvent.LlmCall(
                        action = "proposePatch",
                        attempts = outcome.attempts.size,
                        rejected = true,
                        reason = outcome.rejection.reason,
                    ),
                )
                throw ReplanRequestedException(outcome.rejection.reason)
            }
        }
    }

    @Action(cost = 0.05, description = "Validate a code patch against L1-L2", clearBlackboard = true)
    fun validatePatch(patch: CodePatch): Applying {
        AgentTrace.record("validatePatch")
        return when (val outcome = ValidatePatchAction.validate(patch, runRequest)) {
            is ValidatePatchAction.Outcome.Accepted -> {
                RunAudit.emit(
                    TrajectoryEvent.ValidationOutcome(
                        checkName =
                            outcome.patch.proof.checkNames
                                .joinToString(","),
                        accepted = true,
                        reason = "",
                    ),
                )
                Applying(goal, runRequest, repoModel, validatedPlan, pendingPatch = outcome.patch).also {
                    RunAudit.emit(TrajectoryEvent.StageEntered("Applying"))
                }
            }

            is ValidatePatchAction.Outcome.Rejected -> {
                AgentTrace.record("validatePatch:REJECTED:${outcome.rejection.checkName}:${outcome.rejection.reason}")
                RunAudit.emit(
                    TrajectoryEvent.ValidationOutcome(
                        checkName = outcome.rejection.checkName,
                        accepted = false,
                        reason = outcome.rejection.reason,
                    ),
                )
                throw ReplanRequestedException(outcome.rejection.reason)
            }
        }
    }

    /** The replan lane (Task 4.3, PLAN §6.1 steps 7-8): when the diagnosis says the
     *  PLAN was wrong (pin a transitive, go two-hop), the state machine hands the
     *  diagnosis back to Planning and the LLM re-proposes with the failure in
     *  context.
     *
     *  DELIBERATELY condition-free (evidence in the phase-4 report): a @Condition
     *  only sees the CURRENT blackboard, so a condition on both lanes closes them
     *  at the moment they matter (pre-diagnosis) and the planner finds no complete
     *  plan -> STUCK. The open replan lane is the fallback that keeps a complete
     *  path in the model at every tick; once the diagnosis lands, the patch lane
     *  (gated on the PATCH_CODE hint) is CHEAPER (1.10 vs 1.15 to goal) and wins
     *  when it is open — the two-hop case never opens it, so the replan proceeds. */
    @Action(cost = 0.05, description = "Return to planning with the failure diagnosis")
    fun replan(diagnosis: BuildDiagnosis): Planning {
        AgentTrace.record("replan")
        return Planning(goal, runRequest, repoModel, lastFailure = diagnosis).also {
            RunAudit.emit(TrajectoryEvent.StageEntered("Planning"))
        }
    }
}

data class Blocked(
    val goal: UpgradeGoal,
    val runRequest: RunRequest,
    val blocker: UpgradeBlocker,
) : UpgradeStage {
    /** Escalation: parks the process in WAITING with a HumanDecision form (C-3/C-6;
     *  the REST layer submits the decision — Phase 5). */
    @Action(cost = 0.00, description = "Escalate to a human when plan space is exhausted", pre = ["approvalGateArmed"])
    fun requestHumanDecision(): HumanDecision {
        AgentTrace.record("requestHumanDecision")
        RunAudit.emit(TrajectoryEvent.Escalated(question = blocker.humanQuestion))
        return com.embabel.agent.core.hitl.WaitFor.formSubmission(
            "Plan space exhausted: ${blocker.humanQuestion} ($blocker)",
            HumanDecision::class.java,
        )
    }

    /** Resume after a human decision: confirmed decisions re-enter the machine with
     *  an empty (re-planned) workspace; denied decisions stay blocked. The Phase-5
     *  REST layer resolves the WaitFor form; the decision object is bound back. */
    @Action(cost = 0.00, description = "Resume after a human decision")
    fun resume(decision: HumanDecision): Blocked = this
}

data class Done(
    val goal: UpgradeGoal,
    val runRequest: RunRequest,
    val validatedPlan: ValidatedPlan,
    val verdict: WorkspaceVerdict,
) : UpgradeStage {
    @Action(cost = 0.05, description = "Finalize: the goal BuildGreen is satisfied")
    @AchievesGoal(description = "BuildGreen: the validated upgrade builds green with tests passing")
    fun finalizeUpgrade(): UpgradeComplete {
        AgentTrace.record("finalizeUpgrade")
        RunAudit.emit(TrajectoryEvent.Completed(terminal = "UpgradeComplete"))
        return FinalizeUpgradeAction.finalize(validatedPlan, verdict)
    }
}
