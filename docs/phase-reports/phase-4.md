# Phase 4 report — Replanning & states (the centerpiece)

- Date: 2026-09-01; Executor: dsh Standard; Branch state at gate: `7ec3232` (clean tree)
- Tasks: 4.1 `21e79e4`, 4.2 `082f436`, 4.3 `69d4107`, 4.4 `4e03589`, 4.5 `7ec3232`

## Gate evidence

| Gate command | Result |
|---|---|
| `./mvnw verify` | **`Tests run: 96, Failures: 0, Errors: 0, Skipped: 0`** — `BUILD SUCCESS` (ktlint bound to verify, green) |
| `./mvnw -q -Pdocker-it verify` | **`Tests run: 116, Failures: 0, Errors: 0, Skipped: 1`** — `BUILD SUCCESS` (Skipped: 1 = `LlmConnectivitySmokeIT`, gated by `LLM_SMOKE`) |
| `python3 scripts/check_protocols.py --phase-boundary` | `0 violations` (LEARN[012]/[013]/[014] indexed; KL-08 row added; ledger ascending) |
| `git status --porcelain` | empty |

Phase-4 ITs in the docker-it gate (all green): `RepairLoopIT` (2), `TwoHopReplanIT` (2),
`TerminationIT` (2), `KillResumeIT` (2), `DemoScriptTest` (1 — runs `demo-replan.sh`),
`JsonFileAgentProcessRepositoryTest` (3, unit).

## The §6.1 trace realized (reviewer mandate) — two PlanAttempted lines, verbatim

`fixture-transitive-conflict` run (`scripts/demo-replan.sh` → `var/runs/replan-it/trajectory.jsonl`):

```
  [1] stage entered: Analyzing
  [2] stage entered: Planning
  [3] PLAN ATTEMPTED (1 step(s)): single direct bump
  [5] VALIDATION: L1:plan-paths,L2:plan-diff,L3:versions accepted
  [6] stage entered: Applying
  [7] stage entered: Verifying
  [8] BUILD OBSERVED: FAILED ['[maven-enforcer-plugin:enforce]']
  [9] stage entered: Repairing
  [10] BuildDiagnosis PROPOSED: com.google.guava:guava; 31.0.1-jre
  [12] stage entered: Planning
  [13] PLAN ATTEMPTED (2 step(s)): pin the transitive guava, then bump the direct dependency
  [15] VALIDATION: L1:plan-paths,L2:plan-diff,L3:versions accepted
  [16] stage entered: Applying
  [17] stage entered: Verifying
  [18] BUILD OBSERVED: GREEN []
  [19] stage entered: Done
  [20] COMPLETED: UpgradeComplete
```

The two `PlanAttempted` lines verbatim:

```json
{"seq":3,"event":{"eventType":"PlanAttempted","rationale":"single direct bump","stepCount":1,"at":"2026-09-01T13:49:20.810708465Z"}}
{"seq":13,"event":{"eventType":"PlanAttempted","rationale":"pin the transitive guava, then bump the direct dependency","stepCount":2,"at":"2026-09-01T13:49:23.522291342Z"}}
```

**Drift coordinates (phase-1 environment note, honored throughout):** the direct
`com.google.guava:guava` goes `31.0.1-jre → 33.4.8-jre`; guice `7.0.0` transitively pins
`31.0.1-jre` (verified from `guice-parent` 7.0.0; PLAN §6.1's assumed `32.1.2-jre` pin does
not exist in any guice 6.x/7.x — §8.3 set C replacement, recorded at execution time). The
failed build's log names `33.4.8-jre` (direct) against `31.0.1-jre` (via guice) — both
versions, the enforcer rule, and the guava artifact are asserted verbatim in `TwoHopReplanIT`.

## Verbatim @State transition sequence + one refused/replanned transition (reviewer mandate)

`RepairLoopIT` (fixture-api-removal — the one repair cycle), trajectory `var/runs/repair-it/trajectory.jsonl`,
`StageEntered` sequence verbatim (`StageEntered` events in order):

```
"stage":"Analyzing" "stage":"Planning" "stage":"Applying" "stage":"Verifying"
"stage":"Repairing" "stage":"Applying" "stage":"Verifying" "stage":"Done"
```

- **The loop**: `Verifying → Repairing → Applying → Verifying` — the `@State` machine
  transitions by actions returning state objects; `clearBlackboard = true` on
  `Verifying.runBuild` / `Repairing.validatePatch` / `Planning.validatePlan` is what lets
  the planner revisit state types (LEARN[012]); loop data rides the state instances.
- **The refused transition** (`TwoHopReplanIT`): the FIRST plan (direct bump) is refused —
  not by validation, but by the enforcer at the build (`[8]` BUILD OBSERVED: FAILED,
  `maven-enforcer-plugin:enforce`, `dependencyConvergence` naming guava). The machine
  **re-planned**: `Repairing → Planning` (`replan` hands the diagnosis back; the patch lane
  stayed closed — the diagnosis carries `PIN_TRANSITIVE` + `MULTI_HOP`, no `PATCH_CODE`),
  and the second proposal (MANAGEMENT pin + DIRECT bump) passed L1–L3 (`[15]`) and built
  GREEN.
- **The refusal loop** (`TerminationIT`): every plan **refused at L3** (`L3:version-exists
  for commons-lang3:99.99.99`), five attempts, then the escalation
  (`exhaustPlanSpace → Blocked`) because the machine *never* reaches `Applying`:
  `StageEntered` = `Analyzing`, `Planning` × 6, `Blocked` — `Applying` appears **0** times
  (the fixture's `mustNotVisitStages`).

## Kill-and-resume transcript (reviewer mandate) — verbatim

`scripts/demo-kill-resume.sh` (two JVM sessions; phase 1 cut mid-Applying by the framework's
early-termination policy at `maxActions=4` — the SIGKILL equivalent; the typed snapshot
`var/runs/kill-demo/process.json` is the only survivor; phase 2 is a fresh JVM re-seeded
from it):

```
Phase 1: run the upgrade; cut it mid-Applying (early termination = SIGKILL
equivalent); the typed snapshot is the ONLY survivor of this JVM session.

KILLED at stage Applying (pid 54109)

Phase 2: fresh JVM; resume from the persisted snapshot.

RESUMED run kill-demo -> UpgradeComplete
```

Framework log lines around the cut (verbatim) and the continuation (verbatim):

```
Action Planning.validatePlan returned class com.renovator.agent.states.Applying: clearing blackboard and binding only the output instance
(phase 1 JVM exits here — the run is cut; no Done, no finalize, no UpgradeComplete)
Action Verifying.runBuild returned class com.renovator.agent.states.Repairing: clearing blackboard and binding only the output instance
Action Repairing.validatePatch returned class com.renovator.agent.states.Applying: clearing blackboard and binding only the output instance
Action Verifying.runBuild returned class com.renovator.agent.states.Done: clearing blackboard and binding only the output instance
(phase 2 — the continuation: apply -> build fails (migration breakage) -> repair -> green)
```

The continuation's trajectory (same run id): **one** `StageEntered("Analyzing")` (the original
run only — the `freshRun` precondition closes the entry when a state is already on the
reseeded blackboard), one `Resumed` marker, `Completed(UpgradeComplete)`.

## LEARN quote in full + restate — LEARN[014] (honest termination)

> // LEARN[014] Honest termination: the attempt budget is a framework mechanism, not a convention
> // Why this way: an agent that "keeps trying" with no exit costs a human an unkillable
> //   process, and a budget that only the agent remembers is a budget nobody can audit.
> //   Embabel 1.5.1 ships EarlyTerminationPolicy: maxActions(n), ON_STUCK, firstOf(...) —
> //   attached to ProcessOptions.processControl, enforced by the framework between
> //   actions, independent of our own code (verified in this task: the policy is an
> //   interface method on the process, not an annotation we could forget). Our own
> //   escalation (Blocked + UpgradeBlocker, Task 4.4) sits BELOW that ceiling:
> //   max-attempts is the "I tried and failed" signal that makes the agent REPORT before
> //   the framework CUTS. fixture-no-path is the fixture that proves the whole thing:
> //   the target version 404s forever, so every proposal is L3-rejected and neither side
> //   can be seduced into "one more try" — the only honest outcome is the blocker
> //   carrying every typed rejection.
> // Good sides: the budget is declared in config (renovator.budget.*) and applied by ONE
> //   factory (ProcessOptionsFactory) — every run is bounded by construction; ON_STUCK
> //   turns a planner dead-end into TERMINATED instead of a hung process; the blocker's
> //   ledger is typed (ValidationRejection objects), so the human gets the exact reason.
> // Drawbacks: the ledger must ride the STATE (LEARN[012]: clearBlackboard wipes the
> //   board — a rejection loops back with a NEW Planning frame, attempts+1), and the
> //   changing value is what makes each frame a DISTINCT node for the planner's search —
> //   without the counter, the reject loop is a pure state cycle and the search
> //   dead-ends before the escalation ever opens (verified in this task). The
> //   escalation action needs @AchievesGoal to be planner-visible: a path ending in
> //   WaitFor is not a path to BuildGreen, so without the marker the planner prefers
> //   the doomed propose/validate loop over the 0.00-cost escalation. And a rejection
> //   must be a STATE RETURN, not ReplanRequestedException: the framework's action
> //   retry wraps the throw into an infinite blacklist/re-propose storm (observed:
> //   3000+ validation outcomes for 3321-line run, one proposal).
> // Concept: two circuit breakers in series — the agent's own (report), then the
> //   platform's (cut). The human gets a report before the fuse blows.
> // See also: PLAN §6, PLAN Task 4.4 (C-7), LEARN[012] (state-carried data), KL-01/08

**Restate (in my own words):** a bounded agent is not a convention the agent itself
remembers — it is a declared, framework-enforced policy (`firstOf(maxActions, ON_STUCK)`)
attached at process creation, so every run is bounded by construction. Below that backstop,
our own honesty ceiling (`max-attempts`) makes the agent *report* (typed `UpgradeBlocker`
with every `ValidationRejection`) before the platform *cuts*. The three hard-won
implementation constraints are: (1) the attempt ledger must ride the `Planning` state —
a `clearBlackboard` loop wipes the board, and the changing counter is what makes each
Planning frame a distinct node so the planner's search can traverse the spiral at all;
(2) the escalation park needs `@AchievesGoal` or the planner prefers the doomed
propose/validate loop over the 0.00-cost escalation; (3) a rejection must be a state
return, never `ReplanRequestedException` — the framework's action-retry wraps that throw
into an infinite blacklist/re-propose storm.

## Framework findings (verified this phase, recorded per §13.3)

1. **`@Condition` reads only the CURRENT blackboard** — never a modeled future state.
   Gating BOTH repair lanes on a *future* diagnosis object dead-locks the planner
   (STUCK, no complete plan). The lanes are open while no diagnosis exists and
   hint-decided once it does (deterministic per fixture; `DiagnosisHintCondition`).
2. **`ReplanRequestedException` from a *validation* action is malformed** at this
   maturity: the framework's action-retry sees a retryable exception, blacklists and
   re-proposes in a loop (3321-line trajectory, one real proposal, 3000+ rejections).
   Rejections are state returns (see LEARN[014] drawback 3).
3. **`WaitFor` park must carry `@AchievesGoal`** — a path ending at a human task is not a
   path to `BuildGreen`; without the marker the planner never takes the escalation.
4. **`maxActions` counts agent actions** (fresh runs: analyze+repo+propose+validate = 4)
   and fires between actions; with an explicitly wired `firstOf(...)` policy the cut is
   typed (`TERMINATED`), never a hang.
5. **ktlint debt discovered in 4.1**: the 4.1 commit never ran the ktlint gate —
   11 files violated; fixed (auto-format + one KDoc/comment reorder) inside the 4.2
   commit because the phase-4 gate's `mvnw verify` binds `ktlint:check`.

## §13.3 drift disclosure (environment notes)

- `fixture-transitive-conflict`: guava `31.0.1-jre → 33.4.8-jre` + guice `7.0.0` pins
  `31.0.1-jre` (PLAN text's `32.1.2-jre` pin does not exist — §8.3 set C replacement).
- `fixture-api-removal`: javac names the removed TYPE `StringEscapeUtils` (the whole
  `org.apache.commons.lang` package is absent under lang3), not the method `escapeSql` —
  the diagnosis/patch carry `escapeSql` (the PLAN acceptance `grep -c escapeSql` counts
  the diagnosis + patch lines = 2, as demonstrated).
- `enforcer-rules` artifact is `org.apache.maven.enforcer:enforcer-rules` (not
  `maven-enforcer-rules`).
- 4.1's ktlint debt fixed inside the 4.2 commit (see finding 5) — recorded here rather
  than silently absorbed.

## KL state

- **KL-08** (new, this phase): resume supports only the Applying frame — a kill inside
  Repairing loses the failed build's sandbox copy + diagnostic with the JVM; such a run
  restarts from scratch. Marker `TODO(review) KL-08` at `persistence/RunSnapshot.kt`;
  ledger row added (ascending order; the checker's new ordering rule is active).
- **KL-09** (unchanged): the WaitFor programmatic submission path — the C-6 fallback
  verification and its final state are recorded in Phase 5 (Task 5.3 makes it permanent
  or not). This phase proves the WAITING park and the seeded resume; the submission side
  lands with the REST layer.

## Hook attestation

Pre-commit hook ran on every commit this phase (no `--no-verify` usage; the GW-4 guard
mechanically rejects any such flag): `phase-4.1` → `phase-4.5` all passed the protocol
check at commit time (`0 violations` each). The ledger's **ascending row-order rule**
(the phase-3.9 addition) caught the KL-08 misplacement during this phase and was
corrected before commit; `test_check_protocols.py` self-test remains green.

## Remaining prerequisites for Phase 5/6 (tracked)

- OpenAI credits top-up (auth proven with 429-no-credits) — required for live evals.
- Task 5.3's WaitFor submission verification (C-6) — the programmatic path was confirmed
  (`Awaitable.onResponse` + `process.run()`); the gate decision (KL-09 permanent or not)
  is recorded there.
