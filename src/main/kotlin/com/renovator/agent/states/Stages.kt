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
import com.renovator.domain.AttemptRecord
import com.renovator.domain.BuildDiagnosis
import com.renovator.domain.BuildResult
import com.renovator.domain.CodePatch
import com.renovator.domain.HumanDecision
import com.renovator.domain.RepoModel
import com.renovator.domain.RunRequest
import com.renovator.domain.TestResult
import com.renovator.domain.UpgradeBlocker
import com.renovator.domain.UpgradeComplete
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import com.renovator.domain.WorkspaceVerdict
import com.renovator.execution.WorkspaceSnapshot
import com.renovator.validation.CompileErrorParser
import com.renovator.validation.ValidatedPatch
import com.renovator.validation.ValidatedPlan
import java.nio.file.Path

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
    val approvals: com.renovator.config.RenovatorProperties.Approvals =
        com.renovator.config.RenovatorProperties
            .Approvals(),
) : UpgradeStage {
    @Action(cost = 0.05, description = "Analyze the target repository (pom facts)")
    fun analyzeRepository(): Planning {
        AgentTrace.record("analyzeRepository")
        return Planning(
            goal = goal,
            runRequest = runRequest,
            repoModel = AnalyzeRepositoryAction.analyze(runRequest),
            approvals = approvals,
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
    /** The honest attempt ledger (Task 4.4): every rejected plan adds one record
     *  by looping back into Planning. It rides the STATE for two reasons: a
     *  clearBlackboard loop wipes the board (LEARN[012]), and the data value is
     *  what makes each Planning frame a DISTINCT node for the planner's search
     *  (without it the reject loop is a pure cycle and the search dead-ends —
     *  verified in this task). */
    val attempts: List<AttemptRecord> = emptyList(),
    val approvals: com.renovator.config.RenovatorProperties.Approvals =
        com.renovator.config.RenovatorProperties
            .Approvals(),
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

    /**
     * Deterministic L1-L3 validation; transitions forward on accept. On REJECT
     * the machine does NOT throw (the Phase-3 pattern is replaced by the state
     * transition, Task 4.4): returning a fresh Planning frame carrying the
     * attempt ledger lets the planner re-propose and, once the ceiling is hit,
     * pick the 0.00-cost escalation — a throw would lose the ledger and leave
     * the honest-termination condition nothing to count (LEARN[014]).
     */
    @Action(cost = 0.05, description = "Validate a proposed plan against L1-L3", clearBlackboard = true)
    fun validatePlan(plan: UpgradePlan): UpgradeStage {
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
                if (approvals.plan) {
                    return GatePending(
                        goal = goal,
                        runRequest = runRequest,
                        repoModel = repoModel,
                        validatedPlan = outcome.plan,
                        gateKind = GateKind.PLAN_APPROVAL,
                        approvals = approvals,
                    ).also { RunAudit.emit(TrajectoryEvent.StageEntered("GatePending")) }
                }
                Applying(goal, runRequest, repoModel, outcome.plan, approvals = approvals).also {
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
                Planning(
                    goal,
                    runRequest,
                    repoModel,
                    lastFailure = lastFailure,
                    attempts =
                        attempts +
                            AttemptRecord(
                                planRationale = plan.rationale,
                                rejectedAt =
                                    java.time.Instant
                                        .now()
                                        .toString(),
                                buildFailedGoals = emptyList(),
                                validationRejections = listOf(outcome.rejection),
                            ),
                ).also { RunAudit.emit(TrajectoryEvent.StageEntered("Planning")) }
            }
        }
    }

    /** Honest termination (Task 4.4, C-7): the plan-space ceiling was hit — the
     *  attempt ledger is the RUN's own typed trajectory (PlanAttempted +
     *  ValidationOutcome pairs, read back via RunAudit — the single source of
     *  truth; a `clearBlackboard`-free state could not carry it, LEARN[012]). The
     *  blocker carries EVERY attempt and its typed rejection; Blocked then parks
     *  the process (WaitFor) so a human can break the loop. */
    @Action(cost = 0.00, description = "Escalate: plan space exhausted", pre = ["planSpaceExhausted"])
    fun exhaustPlanSpace(): Blocked {
        AgentTrace.record("exhaustPlanSpace")
        val blocker =
            UpgradeBlocker(
                summary =
                    "plan space exhausted after ${attempts.size} attempt(s): " +
                        attempts.joinToString("; ") { it.planRationale },
                attempts = attempts,
                humanQuestion =
                    "The planner proposed ${attempts.size} plan(s), all rejected by L1-L3 validation. " +
                        "Provide more detailed guidance (or a relaxed goal) to make progress.",
            )
        RunAudit.emit(
            TrajectoryEvent.ProposalReceived(
                kind = "UpgradeBlocker",
                summary = blocker.summary,
            ),
        )
        return Blocked(goal, runRequest, blocker).also {
            RunAudit.emit(TrajectoryEvent.StageEntered("Blocked"))
        }
    }
}

data class Applying(
    val goal: UpgradeGoal,
    val runRequest: RunRequest,
    val repoModel: RepoModel,
    val validatedPlan: ValidatedPlan,
    val pendingPatch: ValidatedPatch? = null,
    val approvals: com.renovator.config.RenovatorProperties.Approvals =
        com.renovator.config.RenovatorProperties
            .Approvals(),
) : UpgradeStage {
    @Action(cost = 0.10, description = "Apply validated changes to a pristine workspace copy")
    fun applyValidatedChanges(): Verifying {
        AgentTrace.record("applyValidatedChanges")
        // The repair bundle: the validated plan (pom) plus any validated patch (code).
        // ApplyValidatedChangesAction stages the plan on a pristine copy; the patch is
        // applied to the SAME copy by the executor's patch path. The source tree is
        // never mutated (D7) — the copy is.
        val snapshot = ApplyValidatedChangesAction.apply(validatedPlan, runRequest, pendingPatch)
        return Verifying(goal, runRequest, repoModel, validatedPlan, snapshot, approvals = approvals).also {
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
    val approvals: com.renovator.config.RenovatorProperties.Approvals =
        com.renovator.config.RenovatorProperties
            .Approvals(),
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
            if (approvals.commitCandidate) {
                GatePending(
                    goal = goal,
                    runRequest = runRequest,
                    repoModel = repoModel,
                    validatedPlan = validatedPlan,
                    gateKind = GateKind.COMMIT_CANDIDATE,
                    verdict = verdict,
                    snapshot = snapshot,
                    approvals = approvals,
                ).also { RunAudit.emit(TrajectoryEvent.StageEntered("GatePending")) }
            } else {
                Done(goal, runRequest, validatedPlan, verdict, approvals).also {
                    RunAudit.emit(TrajectoryEvent.StageEntered("Done"))
                }
            }
        } else {
            Repairing(goal, runRequest, repoModel, validatedPlan, snapshot, verdict, emptyList(), approvals).also {
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

/** Which gate a [GatePending] frame is waiting at (D11): the plan proposal or the
 *  green commit candidate. The frame is the SAME; the continuation differs. */
enum class GateKind {
    PLAN_APPROVAL,
    COMMIT_CANDIDATE,
}

/**
 * HITL gate (PLAN Task 5.3, D11): the machine parks HERE when an approval gate
 * is armed. The park (WaitFor.formSubmission) is the render-side hook; the
 * programmatic submission in Embabel 1.5.1 does not exist (the historical
 * submitFormAndResumeProcess was removed — see issue #1447 and KL-09), so the
 * C-6 fallback continues the run: RunService.submitDecision terminates the
 * parked process and re-seeds a fresh one with [HumanDecision] on the board —
 * the planner then picks approve/reject (decision bound) and parks/re-plans
 * never (the park's `gateUnresolved` precondition closes once the decision is
 * present). The comment rides the state data on reject.
 */
data class GatePending(
    val goal: UpgradeGoal,
    val runRequest: RunRequest,
    val repoModel: RepoModel,
    val validatedPlan: ValidatedPlan,
    val gateKind: GateKind,
    /** The green build verdict (commit gate only; the rejection carries it). */
    val verdict: WorkspaceVerdict? = null,
    /** The workspace copy the verdict was built on (commit gate); the rejection
     *  carries it into the repair so the repair lane has the true file state. */
    val snapshot: WorkspaceSnapshot? = null,
    val approvals: com.renovator.config.RenovatorProperties.Approvals =
        com.renovator.config.RenovatorProperties
            .Approvals(),
) : UpgradeStage {
    @Action(cost = 0.00, description = "Request approval: park at the gate", pre = ["gateUnresolved"])
    fun park(context: OperationContext): HumanDecision {
        AgentTrace.record("park")
        RunAudit.emit(
            TrajectoryEvent.Escalated(
                question = "approval required (${gateKind.name.lowercase().replace('_', '-')}): ${validatedPlan.plan.rationale}",
            ),
        )
        return com.embabel.agent.core.hitl.WaitFor.formSubmission(
            "Approval required: ${gateKind.name.lowercase().replace('_', '-')} — ${validatedPlan.plan.rationale}",
            HumanDecision::class.java,
        )
    }

    @Action(cost = 0.00, description = "Approve: continue past the gate", pre = ["humanApproved"])
    fun approve(decision: HumanDecision): UpgradeStage =
        if (gateKind == GateKind.COMMIT_CANDIDATE) {
            Done(goal, runRequest, validatedPlan, verdict ?: error("commit gate needs the green verdict"), approvals).also {
                RunAudit.emit(TrajectoryEvent.StageEntered("Done"))
            }
        } else {
            Applying(goal, runRequest, repoModel, validatedPlan, approvals = approvals).also {
                RunAudit.emit(TrajectoryEvent.StageEntered("Applying"))
            }
        }

    @Action(cost = 0.00, description = "Reject: route back with the human comment", pre = ["humanRejected"])
    fun reject(decision: HumanDecision): UpgradeStage {
        AgentTrace.record("gateRejected:${decision.comment}")
        val rejection =
            com.renovator.execution.Excerpt.of(
                "HUMAN REJECTION (${gateKind.name.lowercase().replace('_', '-')}): ${decision.comment}",
            )
        return if (gateKind == GateKind.COMMIT_CANDIDATE) {
            Repairing(
                goal,
                runRequest,
                repoModel,
                validatedPlan,
                snapshot
                    ?: com.renovator.execution.WorkspaceSnapshot(
                        ref = com.renovator.execution.WorkspaceRef(Path.of(".")),
                        sourceHash = "gate-rejection",
                    ),
                failedVerdict =
                    WorkspaceVerdict(
                        build = BuildResult(success = false, failedGoals = listOf("human-rejection"), log = rejection, durationMs = 0),
                        tests = TestResult(0, 1, emptyList()),
                    ),
                attempts = emptyList(),
                approvals = approvals,
            ).also { RunAudit.emit(TrajectoryEvent.StageEntered("Repairing")) }
        } else {
            Planning(goal, runRequest, repoModel, lastFailure = null, approvals = approvals).also {
                RunAudit.emit(TrajectoryEvent.StageEntered("Planning"))
            }
        }
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
    val approvals: com.renovator.config.RenovatorProperties.Approvals =
        com.renovator.config.RenovatorProperties
            .Approvals(),
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
                Applying(goal, runRequest, repoModel, validatedPlan, pendingPatch = outcome.patch, approvals = approvals).also {
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
     *  context. The precondition (diagnosisSuggestsReplan) is open before the
     *  diagnosis exists and resolves by the hint set once it does — see
     *  DiagnosisHintCondition for the two-lane determinism rationale (a
     *  @Condition only reads the CURRENT blackboard, so gating BOTH lanes
     *  pre-diagnosis closes the machine: verified in this phase). */
    @Action(cost = 0.05, description = "Return to planning with the failure diagnosis", pre = ["diagnosisSuggestsReplan"])
    fun replan(diagnosis: BuildDiagnosis): Planning {
        AgentTrace.record("replan")
        return Planning(goal, runRequest, repoModel, lastFailure = diagnosis, approvals = approvals).also {
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
     *  the REST layer submits the decision — Phase 5). The blocker was assembled by
     *  Planning.exhaustPlanSpace (Task 4.4); the WaitFor title carries its history. */
    @Action(cost = 0.00, description = "Escalate to a human when plan space is exhausted")
    @AchievesGoal(description = "Escalated: plan space exhausted; a human decision is pending")
    fun requestHumanDecision(): HumanDecision {
        AgentTrace.record("requestHumanDecision")
        RunAudit.emit(TrajectoryEvent.Escalated(question = blocker.humanQuestion))
        return RequestHumanDecisionAction.escalate(blocker)
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
    val approvals: com.renovator.config.RenovatorProperties.Approvals =
        com.renovator.config.RenovatorProperties
            .Approvals(),
) : UpgradeStage {
    @Action(cost = 0.05, description = "Finalize: the goal BuildGreen is satisfied")
    @AchievesGoal(description = "BuildGreen: the validated upgrade builds green with tests passing")
    fun finalizeUpgrade(): UpgradeComplete {
        AgentTrace.record("finalizeUpgrade")
        RunAudit.emit(TrajectoryEvent.Completed(terminal = "UpgradeComplete"))
        return FinalizeUpgradeAction.finalize(validatedPlan, verdict)
    }
}
