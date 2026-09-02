# Phase 5 report — Control plane / HITL

- Date: 2026-09-02; Executor: dsh Standard; Branch state at gate: `f3e73ae` + Phase-5 impl commits (clean tree at gate)
- Tasks: 5.1 REST control API, 5.2 SSE replay-then-tail, 5.3 HITL gates (C-6 verdict), 5.4 CLI

## Gate evidence

| Gate command | Result |
|---|---|
| `./mvnw verify` | **`Tests run: 104, Failures: 0, Errors: 0, Skipped: 0`** — `BUILD SUCCESS` (ktlint bound, green; includes app-boot 2/2, RunControllerTest 5/5, SseReplayIT 3/3, DecisionControllerTest 3/3, repo tests the module-sliced WebMvcTest) |
| `./mvnw -Pdocker-it verify` | **`Tests run: 133, Failures: 0, Errors: 0, Skipped: 2`** — `BUILD SUCCESS` (Skipped: 2 = the two LIVE tests, LLM_SMOKE-gated) |
| `python3 scripts/check_protocols.py --phase-boundary` | `0 violations` (LEARN[015] indexed; KL-09 marked *PERMANENT* + ledger row; ledger ascending) |
| `git status --porcelain` | empty |

## Task 5.3 headline — the WaitFor programmatic-submission check (C-6), decided

**Verdict: KL-09 is PERMANENT — the C-6 fallback is the supported pattern; the historical programmatic submission API does not exist in Embabel 1.5.1.**

**Evidence (verification step, per PLAN — from the 1.5.1 jars + the upstream issue):**
- `WaitFor.formSubmission(String, Class<T>)` exists and parks (proven in Phase 4 — WAITING status; `com.embabel.agent.core.hitl.WaitFor`, jar-verified).
- The historical programmatic APIs — **`submitFormAndResumeProcess`** and **`_confirm`** — were **removed from the release line**: [github.com/embabel/embabel-agent#1447](https://github.com/embabel/embabel-agent/issues/1447) documents them present in 0.3.2 and **absent in 0.3.3/0.3.4** (closed; no public replacement posted). A string search across **every** Embabel jar in `~/.m2` confirms neither string exists in 1.5.1.
- `AgentProcess` exposes **no public pending-awaitable accessor** and **no response-injection method** (javap surface: id/parentId/blackboard/options/statusReport/terminateAgent/… — no `receiveResponse`/`submit`), so an outside caller cannot reach the parked `Awaitable` to call `onResponse`.

**The C-6 fallback (implemented + proven end-to-end through the REAL service path — `ApprovalGateIT`, 3/3, docker-it):**

```
process parks at the commit-candidate gate until approved, then finalizes
  [park -> WAITING] -> RunService.submitDecision(approved=true)
  -> the parked shell is terminated + the continuation re-seeds [gate, decision]
  -> approve -> Done -> finalizeUpgrade -> UpgradeComplete
  (trajectory: one StageEntered("Analyzing"), one "GatePending", a Resumed marker
   "human decision: approved", Completed(UpgradeComplete))
rejection routes to Repairing with the human comment on the blackboard
  submitDecision(approved=false, comment="this upgrade is not acceptable yet")
  -> the continuation marker carries the comment; Repairing entered
gate disarmed by config means no park
  approvals(commitCandidate=false) -> straight to Done, no GatePending, no Resumed
```

The continuation reuses the **same run id** (one trajectory story) — `freshRun` closes the entry (a state is already on the board), so there is **no repeated Analyze stage**. The decision's **value** chooses approve vs reject via two conditions (`humanApproved`/`humanRejected`) — the planner cannot value-discriminate, so the conditions carry the semantics.

## KL-01 single-run rejection — verbatim proof

`RunControllerTest.second concurrent submission returns 409` (WebMvcTest + real controller/validation):

```
mockMvc.perform(post("/api/runs") ...)
  .andExpect(status().isConflict)
  .andExpect(jsonPath("$.code").value("conflict"))
  .andExpect(jsonPath("$.message").value("a run is already active (single-run enforcement, KL-01)"))
```

`RunServiceIT.a second concurrent submission is rejected`: the real async service refuses a second `submit` with `ConflictException` while the first run is active, then accepts again once the slot frees. The registry's `tryBegin()` is the single-run gate (KL-01 marker at `audit/RunRegistry.kt`).

## KL-03 repo validation + typed 422s

`RunControllerTest.rejects a repo path outside allowed roots with 422` and `.rejects a non-Maven target with 422` — the controller's real `validateRepoPath` (exists / directory / under an allowed root / contains `pom.xml`), and `unknown run id is a typed 422, not a stack trace` (`ApiError(code,message)`, never a stack trace — PLAN acceptance).

## Task 5.2 — SSE replay-then-tail (D12)

`SseReplayIT` (3/3, real HTTP stream): replays the trajectory lines with their sequence numbers, then tails live events from the application event bus with **monotonically increasing, 1-based consecutive** sequences; the stream **completes on the terminal event**; a subscriber to a **finished run replays and closes immediately**. The trajectory file is the source of truth; `TrajectoryBus` is the live tail only (a missed bus event is always re-readable from the file).

## Task 5.4 — CLI

`scripts/renovator` (submit / watch / decide / trajectory / status) verified live: `CliSmokeIT.submit watch decide happy path` — the CLI commands all reached the live service. The run's terminal is deliberately made deterministic (fixture-no-path: any live plan is L3-rejected → the blocker) so the smoke asserts **CLI mechanics** (submit→id, status, trajectory, decide, watch) independent of the live model's plan; the gate/decide **semantics** are proven deterministically at the service+controller layers (ApprovalGateIT, DecisionControllerTest).

## Live-smoke re-run (credits live) — closes Phase-0 thread KL-06

```
LLM_SMOKE=1 ./mvnw -q -Pllm-it test -Dtest=LlmConnectivitySmokeIT
SMOKE bound answer=[pong]
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

**Live-call counts (spend discipline):** `LlmConnectivitySmokeIT` = **1** `ping` call; `CliSmokeIT` = **1** `proposePlan` call. Both are gated (`LLM_SMOKE=1` + `-Pllm-it`, never a default `verify`) and use the configured **gpt-4.1-mini** (cheapest adequate). No other Phase-5 test makes a live call.

## LEARN quote in full + restate — LEARN[015]

> ```text
> // LEARN[015] The observable loop: replay-then-tail, and WaitFor as the BPMN human task
> // Why this way: (part 1 — the stream, D12) Sentinel's stream lets a late subscriber see
> //   the WHOLE story — it replays the run's events in order and then tails the live ones.
> //   Ours does the same with one source of truth: the trajectory FILE (append-only,
> //   sequence-numbered, interrupted-write safe) is replayed in full for a new subscriber;
> //   the app event bus (TrajectoryBus) is the live tail only. That split is the design:
> //   a subscriber that misses bus events can always re-read the file, and the file never
> //   depends on having subscribers. The plan's "application event bus" is exactly this
> //   bus — the publish hook lives in RunAudit.emit, the heartbeat keeps idle streams
> //   alive, and the stream completes on the terminal event (SseReplayIT proves the
> //   ordering, the monotonic sequences, and the immediate close for finished runs).
> //   (part 2 — the gate, D11) WaitFor is a BPMN human task in disguise —
> //   the process parks, the outside world answers, the blackboard resumes it. The
> //   framework's WaitFor parks (WAITING, proven in Phase 4); what 1.5.1 does NOT have
> //   is a public programmatic answer path (KL-09: submitFormAndResumeProcess was
> //   removed in 0.3.3+ — issue #1447, jar-verified), so OUR REST layer stands in for
> //   the shell's form renderer: submitDecision reads the parked gate, terminates the
> //   parked shell, and re-seeds the machine with [gate, decision] — the decision's
> //   VALUE picks the continuation (approve/reject conditions), the trajectory stays one
> //   story (Resumed marker, no repeated Analyze), and the park closes (gateUnresolved).
> //   Good sides: subscribers replay from an immutable log (no ordering races); the
> //   gate loop is testable end-to-end without the shell (ApprovalGateIT through the
> //   real service path); the decision is typed and the comment rides the repair.
> //   Drawbacks: the tail is in-memory (a restart re-replays from the file — fine, the
> //   file is the truth); the "parked shell terminated + re-seed" is not a true resume
> //   of the same process object (KL-09 permanent); and the bus is per-JVM (no fan-out
> //   across processes — the CLI is single-host by scope, KL-01).
> //   Concept: think of the trajectory as a write-ahead log and the SSE stream as the
> //   tailer; think of the gate as a BPMN user task whose "assignee" is the HTTP layer.
> // See also: PLAN §5, PLAN Tasks 5.2/5.3 (D11, D12), KL-09, LEARN[012], LEARN[014]
> ```

**Restate (in my own words):** the loop is observable through a write-ahead-log-plus-tailer design — the trajectory file is the durable, sequence-numbered source of truth (replayed to any late subscriber), and the in-memory event bus is the live tail (always re-readable from the file, so a missed event never loses the story); the SSE stream replays then tails and completes on the terminal event. The gate is a BPMN user task: the process parks (WaitFor), the outside world (our REST layer) answers, and the blackboard resumes it. Because 1.5.1 removed the public programmatic answer path (`submitFormAndResumeProcess`, issue #1447), the pre-declared C-6 fallback is the pattern: the parked shell is terminated and the machine re-seeds with the decision — the decision's value (approve/reject) is carried by conditions (the planner cannot value-discriminate), the park closes (`gateUnresolved`), and the trajectory stays one story. The cost, recorded honestly in KL-09, is that this is a re-seed, not a true resume of the same process object.

## §13.3 drift disclosure (environment notes, Phase 5)

- **Boot 4 modularized the MVC test slice**: `WebMvcTest` is now `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` in the **`spring-boot-webmvc-test`** artifact (not `spring-boot-test-autoconfigure`); added as a test dep. `@MockBean` → `@MockitoBean` (Spring Framework 7).
- **A blank env default cannot bind to a List** in Spring Boot: `${RENOVATOR_ALLOWED_ROOTS:}` broke the context (`Failed to bind … renovator.llm`); list-typed config now uses the code default (empty = working dir) instead of a blank yml default.
- **Kotlin default-arg constructors defeat Spring ctor-injection**: the `RenovatorAgent`'s all-default ctor used the Kotlin synthetic default bridge, so the injected `RenovatorProperties` bean never reached the agent (the approval gates silently never armed). Fixed with a **param-level `@Autowired`** (a ctor-level `@Autowired` collides with the synthetic default ctor — `Invalid autowire-marked constructor`). The three condition classes became `@Component`.
- **`mockito-kotlin`** added (test): `Mockito.any(Class)` returns null, which trips Kotlin's non-null boundary at the call site; the kotlin extension (`any()/anyOrNull()`) is the supported interop.
- **PLAN's CliSmokeIT was docker-it; ours is llm-it + `LLM_SMOKE`** — the app always uses the live LLM channel (the mock is test-only in this architecture), so a Docker-alone CLI smoke would hit the live model anyway; per the reviewer's spend discipline it is opt-in gated, and a live model's plan is non-deterministic, so the smoke asserts CLI mechanics (not a run outcome). The gate/decide semantics are proven with the scripted planner.
- **`RequestHumanDecisionAction`/`Blocked` unchanged** — the block-park is Phase-4; Phase 5 adds the gate frame (`GatePending`) and the decision surface.

## KL state

- **KL-08** (unchanged from Phase-4 remediation): re-enters at the last apply.
- **KL-09 → PERMANENT** (this phase): no programmatic WaitFor submission in Embabel 1.5.1; C-6 fallback implemented + proven end-to-end. Marker `TODO(review) KL-09` at `RunService.submitDecision`; ledger row added.

## Hook attestation

Every Phase-5 commit ran only under the pre-commit hook (no `--no-verify` anywhere; the GW-4 guard mechanically rejects it); the ledger's **ascending row-order rule** caught both the KL-09 and the LEARN[015] misplacements during the build and both were corrected before commit. `check_protocols.py --phase-boundary` is `0 violations` at the gate.

## Remaining prerequisite (tracked)

Live OpenAI credits are now confirmed working (the smoke), so the Phase-5/6 live evals are unblocked; the spend discipline (opt-in gating + model pinning + call counts) is recorded above and is now the standing rule for any new live test.
