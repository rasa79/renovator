package com.renovator.agent

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Condition
import com.embabel.agent.api.common.OperationContext
import com.renovator.agent.conditions.CommitCandidacyCondition
import com.renovator.agent.conditions.DiagnosisHintCondition
import com.renovator.agent.conditions.GateArmedCondition
import com.renovator.agent.states.Analyzing
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeGoal
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

// LEARN[010] Action-cost asymmetry: the planner prefers plans that fail cheap
// Why this way: every action in the palette is priced by what it risks. Cheap
//   validators (0.05) gate every proposal; LLM calls (0.30) are mid; sandbox
//   operations (0.60/0.80) are expensive and A* only routes through them when
//   cheaper paths are exhausted. A plan that fails at L1 costs 0.05+0.30+0.05
//   (analysis + proposal + validation); a plan that fails at the build costs
//   the same PLUS 0.60 and a container — so the search prefers to surface the
//   cheap failure first, which is exactly the "fail cheap" property (D9).
// Good sides: costs are declarative and reflectable (ActionCostTableTest ties
//   them to the PLAN §6 table); ordering is testable (PlannerOrderingIT);
//   adding a new operation is a number, not a new control-flow branch.
// Drawbacks: costs are GUIDANCE, never correctness — a wrong cost only skews
//   plan preference, it cannot admit an invalid plan (the deterministic judge
//   is the gate, not the price); and tuning is empirical (a 0.80 dry-run may
//   warrant a different price if compiles get cheaper).
// Concept: think of it as a mixed strategy in game theory terms — you pay the
//   cheapest probe first because information has a price and replanning is
//   free. The planner's A* is indifferent to which validator catches a bug,
//   only to how much the search costs before it knows.
// See also: PLAN §6 cost rationale, PLAN D9, LEARN[009], ActionCostTableTest

// LEARN[011] The typed blackboard is workflow-engine process variables
// Why this way: an Embabel blackboard is a bag of typed objects, and action
//   parameters bind by NAME or by latest-of-type. That is exactly a BPMN
//   workflow engine's process variables (BPMN: variables keyed by name, one
//   live value each) — but with TYPE-CHECKED reads: asking for a RepoModel
//   yields a RepoModel or "not available", never a string you parse by
//   convention. The duplicate-name problem a workflow engine answers with
//   "set" is answered here by latest-of-type, which is WHY loop-carried data
//   must travel in state instances (the @State story, C-2, LEARN[012]) instead
//   of re-using blackboard keys.
// Good sides: no inter-module maps; action signatures are self-documenting;
//   planner conditions type-check the same way; the trajectory can log typed
//   facts, not ad-hoc JSON.
// Drawbacks: implicit binding rules (name-vs-type) must be learned; two
//   objects of the same type cannot coexist (latest wins) — consumers must not
//   assume history; and there is no per-run scoping beyond the state machine.
// Concept: blackboard ≈ process variables with generics. Same trade-offs as
//   BPMN: less ceremony, more discipline about what is "current".
// See also: PLAN §5, LEARN[009], PLAN §2 C-2

/**
 * Renovator agent (Phase 4): the @State machine in agent/states/Stages.kt carries
 * the palette per state (LEARN[012]); this class only provides the ENTRY action
 * (analyzeRepository returns Analyzing, PLAN Task 4.1) and the guard conditions
 * (PLAN §6): the approval gates and the patch-lane selector (Task 4.3 — the
 * diagnosis's PATCH_CODE hint opens the code-patch lane; the replan lane is the
 * always-open fallback, see Repairing.replan). The planner enters the state
 * machine on the first action and transitions by actions returning state objects.
 */

@Agent(description = "Renovator: propose, validate, execute, observe, replan")
@Component
class RenovatorAgent(
    private val commitCandidacyCondition: CommitCandidacyCondition = CommitCandidacyCondition(),
    private val gateArmedCondition: GateArmedCondition = GateArmedCondition(),
    private val diagnosisHintCondition: DiagnosisHintCondition = DiagnosisHintCondition(),
    private val budget: com.renovator.config.RenovatorProperties.Budget =
        com.renovator.config
            .RenovatorProperties()
            .budget,
) {
    @Action(cost = 0.05, description = "Analyze the target repository (entry state)")
    fun analyzeRepository(
        goal: UpgradeGoal,
        runRequest: RunRequest,
    ): Analyzing {
        com.renovator.audit.RunAudit
            .ensureRunId()
        com.renovator.audit.RunAudit
            .emit(
                com.renovator.audit.TrajectoryEvent
                    .StageEntered("Analyzing"),
            )
        return Analyzing(goal, runRequest)
    }

    @Condition(name = "commitCandidacyArmed")
    fun commitCandidacyArmed(operationContext: OperationContext): Boolean = commitCandidacyCondition.isArmed(operationContext)

    @Condition(name = "approvalGateArmed")
    fun approvalGateArmed(operationContext: OperationContext): Boolean = gateArmedCondition.isArmed()

    @Condition(name = "diagnosisSuggestsPatch")
    fun diagnosisSuggestsPatch(operationContext: OperationContext): Boolean = diagnosisHintCondition.suggestsPatch(operationContext)

    @Condition(name = "diagnosisSuggestsReplan")
    fun diagnosisSuggestsReplan(operationContext: OperationContext): Boolean = diagnosisHintCondition.suggestsReplan(operationContext)

    /** Task 4.4 (C-7): the attempt ledger is the run's typed trajectory (the
     *  PlanAttempted events — one per accepted proposal, read back via RunAudit);
     *  the escalation action opens as soon as the ceiling is hit. */
    @Condition(name = "planSpaceExhausted")
    fun planSpaceExhausted(operationContext: OperationContext): Boolean =
        (
            operationContext.objects
                .filterIsInstance<com.renovator.agent.states.Planning>()
                .lastOrNull()
                ?.attempts
                ?.size ?: 0
        ) >= budget.maxAttempts
}
