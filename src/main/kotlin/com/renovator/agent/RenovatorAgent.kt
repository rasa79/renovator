package com.renovator.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
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
import com.renovator.domain.BuildDiagnosis
import com.renovator.domain.BuildResult
import com.renovator.domain.CodePatch
import com.renovator.domain.CompileCheckResult
import com.renovator.domain.RepoModel
import com.renovator.domain.RunRequest
import com.renovator.domain.TestResult
import com.renovator.domain.UpgradeBlocker
import com.renovator.domain.UpgradeComplete
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import com.renovator.execution.WorkspaceSnapshot
import com.renovator.validation.ValidatedPatch
import com.renovator.validation.ValidatedPlan
import org.springframework.stereotype.Component

// LEARN[009] GOAP/dynamic planning vs static graph wiring — the canonical essay
// Why this way: Sentinel (the parent project) wires agents as a STATIC graph:
//   every edge is declared, the flow is reviewable on the whiteboard, and "what
//   happens if this step fails" is a design question answered at authoring time.
//   GOAP swaps the edges for PRECONDITIONS: the planner searches a state space
//   and re-plans after every action (OODA loop). That buys exactly one thing, and
//   it is the thing this project is about: when a step fails in a data-dependent
//   way (a compile error naming a symbol, an enforcer rule firing), the SAME
//   palette re-plans without a human rewiring the graph. The cost is real: the
//   design risk moves from "which edge" to "which precondition/cost" — a bad
//   precondition makes the planner explore a wrong-but-valid plan, and costs are
//   only guidance (see LEARN[010]), never correctness.
// Good sides: error recovery is data-driven (a typed failure on the blackboard is
//   all the trigger a replan needs); new steps need no edge surgery; palette +
//   preconditions are reflectable, hence plan-table-tested (AgentPaletteCompletenessTest).
// Drawbacks: plans are hidden behind a search — the DETERMINISTIC JUDGE (builds,
//   validators) is what keeps the search honest, which is why fixtures precede the
//   agent (LEARN[003]); and a greedy "utility" planner cannot sequential-feed
//   inputs, which is why GOAP is the only supported mode here (verified C-4:
//   Task-0.3's UTILITY attempt went STUCK — the planner must be able to plan a
//   chain, not just pick one achievable action per tick).
// Concept: think "routing table vs. a map": a static graph is a routing table —
//   fast, legible, brittle to failures; GOAP is a map plus a heuristic — you can
//   find a route you never drew. You only want a map if the landscape can change
//   under you; here it can (the agent's own actions change the world state).
// See also: PLAN §4.1, §6; LEARN[010] costs; LEARN[011] typed blackboard
@Agent(description = "Renovator: propose, validate, execute, observe, replan")
@Component
class RenovatorAgent(
    private val analyzeRepositoryAction: AnalyzeRepositoryAction,
    private val runBuildAction: RunBuildAction,
    private val applyValidatedChangesAction: ApplyValidatedChangesAction,
    private val finalizeUpgradeAction: FinalizeUpgradeAction,
    private val validatePlanAction: ValidatePlanAction,
    private val validatePatchAction: ValidatePatchAction,
    private val dryRunCompileAction: DryRunCompileAction,
    private val requestHumanDecisionAction: RequestHumanDecisionAction,
    private val llmActions: LlmActions,
) {
    // ---------------- deterministic palette ----------------

    @Action(cost = 0.05, description = "Analyze the target repository (pom facts)")
    fun analyzeRepository(
        goal: UpgradeGoal,
        runRequest: RunRequest,
    ): RepoModel = analyzeRepositoryAction.analyze(runRequest)

    @Action(cost = 0.05, description = "Validate a proposed plan against L1-L3")
    fun validatePlan(
        plan: UpgradePlan,
        runRequest: RunRequest,
    ): ValidatePlanAction.Outcome =
        validatePlanAction.validate(
            plan,
            runRequest.repoPath
                .resolve("pom.xml")
                .toFile()
                .readText(),
        )

    @Action(cost = 0.10, description = "Apply validated changes to a pristine workspace copy")
    fun applyValidatedChanges(
        plan: ValidatedPlan,
        runRequest: RunRequest,
    ): WorkspaceSnapshot = applyValidatedChangesAction.apply(plan, runRequest)

    // LEARN[011] The typed blackboard is workflow-engine process variables
    // Why this way: an Embabel blackboard is a bag of typed objects, and action
    //   parameters bind by NAME or by latest-of-type. That is exactly a BPMN
    //   workflow engine's process variables (BPMN: variables keyed by name, one
    //   live value each) — but with TYPE-CHECKED reads: asking for a RepoModel
    //   yields a RepoModel or "not available", never a string you parse by
    //   convention. The duplicate-name problem a workflow engine answers with
    //   "set" is answered here by latest-of-type, which is WHY loop-carried data
    //   must travel in state instances (Phase 4's @State story, C-2) instead of
    //   re-using blackboard keys.
    // Good sides: no inter-module maps; action signatures are self-documenting;
    //   planner conditions type-check the same way; the trajectory can log typed
    //   facts, not ad-hoc JSON.
    // Drawbacks: implicit binding rules (name-vs-type) must be learned; two
    //   objects of the same type cannot coexist (latest wins) — consumers must not
    //   assume history; and there is no per-run scoping beyond the state machine.
    // Concept: blackboard ≈ process variables with generics. Same trade-offs as
    //   BPMN: less ceremony, more discipline about what is "current".
    // See also: PLAN §5, LEARN[009], PLAN §2 C-2
    @Action(cost = 0.60, description = "Run the sandboxed build and tests")
    fun runBuild(snapshot: WorkspaceSnapshot): Pair<BuildResult, TestResult> = runBuildAction.runBuild(snapshot.ref)

    @Action(cost = 0.05, description = "Validate a code patch against L1-L2")
    fun validatePatch(
        patch: CodePatch,
        runRequest: RunRequest,
    ): ValidatePatchAction.Outcome = validatePatchAction.validate(patch, runRequest)

    @Action(cost = 0.80, description = "Dry-run compile in the sandbox (expensive opinion)")
    fun dryRunCompile(
        plan: ValidatedPlan,
        runRequest: RunRequest,
    ): CompileCheckResult = dryRunCompileAction.dryRunPlan(plan, runRequest)

    @Action(cost = 0.00, description = "Escalate to a human when plan space is exhausted")
    fun requestHumanDecision(blocker: UpgradeBlocker): UpgradeBlocker = requestHumanDecisionAction.request(blocker)

    @Action(cost = 0.05, description = "Finalize: the goal BuildGreen is satisfied")
    @AchievesGoal(description = "BuildGreen: the validated upgrade builds green with tests passing")
    fun finalizeUpgrade(
        plan: ValidatedPlan,
        build: BuildResult,
        tests: TestResult,
        actionContext: ActionContext,
    ): UpgradeComplete = finalizeUpgradeAction.finalize(plan, build, tests)

    // ---------------- LLM palette (typed binding only, D6) ----------------

    @Action(cost = 0.30, description = "Propose an upgrade plan (LLM, typed binding)")
    fun proposeUpgradePlan(
        repoModel: RepoModel,
        goal: UpgradeGoal,
        context: OperationContext,
    ): LlmOutcome<UpgradePlan> = llmActions.proposePlan(context, repoModel, goal)

    @Action(cost = 0.30, description = "Diagnose a failed build (LLM, typed binding)")
    fun diagnoseFailure(
        build: BuildResult,
        context: OperationContext,
    ): LlmOutcome<BuildDiagnosis> = llmActions.diagnoseFailure(context, build)

    @Action(cost = 0.30, description = "Propose a code patch (LLM, typed binding)")
    fun proposePatch(
        diagnosis: BuildDiagnosis,
        runRequest: RunRequest,
        context: OperationContext,
    ): LlmOutcome<CodePatch> = llmActions.proposePatch(context, diagnosis, runRequest.repoPath.toFile().readText())
}
