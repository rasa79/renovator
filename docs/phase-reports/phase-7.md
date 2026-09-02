# Phase 7 report — Docs & final audit

- Date: 2026-09-02; Executor: dsh (DeepSeek — see Model attribution); Branch state at gate: `3ce9e1e` (phase-6.remediation: hermetic gate) + this Phase-7 gate commit (clean tree at gate)
- Environment: WSL2-native (`/home/bucko/dev2/Renovator`); Java 25 (Eclipse Temurin); Docker (WSL2 integration); provider = cloud (`gpt-4.1` pin for the live floor); the executor model is recorded separately (Model attribution).

## Bounded claim (trilogy framing) — trace

The project's claim — *we know exactly which property makes agentic automation safe (a
deterministic judge + cheap reversibility), and we can name the classes of problems where
it isn't* — traces through:

- **The executor boundary (§4.2):** the executor takes only `Validated*` (private ctor +
  recomputed sha256 proof); enforcement is at the boundary, in code, never in prompts
  (LEARN[006], ExecutorBoundaryTest).
- **The sandbox (D7):** a throwaway container + pristine copy per attempt; the source tree
  is never mounted or mutated (LEARN[004], DockerSandboxRunnerIT).
- **The judge fixtures (§8):** four fixtures whose expected outcomes are the deterministic
  judge (mock eval 4/4 = the CI gate; live floor gated).
- **The "where it doesn't" README section (Appendix A):** names exactly where one of the
  two load-bearing properties (deterministic judge, cheap reversibility) or the latency
  budget is absent — irreversible real-world effects, no deterministic judge, hard real-time.

## Task 7.1 — README (Appendix A) + ReadmeStructureTest

README rewritten to the Appendix-A mandated opening order (accessibility before
architecture): "What is this, in plain language" → "Where this kind of system applies —
and where it doesn't" (verbatim A.2 material) → the bounded-claim closing → architecture →
setup/quickstart → demo walkthroughs → eval → design decisions → known limitations.
`ReadmeStructureTest` (3/3): what-is-this precedes any architecture heading; the verbatim
A.2 items + closing framing; every user-visible KL entry has a README sentence.
Demonstration: `./mvnw -q test -Dtest=ReadmeStructureTest && echo README-OK` → `README-OK`
(verified in the gate). Every README command was freshly executed (spring-boot:run boots
and `/actuator/health` returns UP; `scripts/renovator submit/status/trajectory/watch/decide`
against a live app; both demo scripts; the mock + live eval commands — all quote-run).

## Task 7.2 — Full protocol audit

`python3 scripts/check_protocols.py --full` → **`0 violations`**. LEARN_INDEX complete
(`001–020`, ascending, one location each); KNOWN_LIMITATIONS 1:1 both directions (every
`TODO(review) KL-NN` ↔ exactly one row; ascending; KL-12 struck/closed, KL-13 live);
tags `phase-0-complete` … `phase-6-complete` present and pointing at gate commits.

**LEARN restate-tests (self-assessed, per Appendix B):**
- **LEARN[019]** (register metrics eagerly) — restate: "a MeterRegistry only exposes a meter
  once it has been created; creating it lazily inside observe() makes the exposed name-set a
  function of WHICH run just executed, so a fixture-clean run (no Repairing event) leaves
  replans_total absent and the test fails in isolation. Register every meter once at attach();
  Micrometer treats the untagged base and tagged variants as distinct, so counts still start
  at 0." — restated accurately; the PrometheusMetricsIT + MetricsIT pair pass in isolation
  (verified).
- **LEARN[020]** (drain the async executor) — quoted in full below with its restate-test.

## Task 7.3 — Clean-clone reproduction

`git clone file://$PWD /tmp/renovator-clone && cd /tmp/renovator-clone && ./mvnw verify &&
./mvnw -Peval-mock,docker-it verify && scripts/demo-replan.sh && scripts/demo-kill-resume.sh`
(all on the WSL2-native fs, D15). **Three defects caught + fixed by this pass:**

1. `src/test/resources/buildlogs/*.log` (two fixture logs read by `CompileErrorParserTest` /
   `BuildResultParserTest`) were **gitignored and never committed** → a fresh clone NPE'd.
   Fixed in `f67a1c2` (force-added the fixtures + a `.gitignore` negation).
2. `ReadmeStructureTest` had a **ktlint violation** that the main repo's pre-7.1 verify never
   ran → the clone's verify failed. Fixed in `06c4710` (ktlint:format).
3. **The eval/docker gate was order- and state-dependent** (`3ce9e1e`, LEARN[019] + LEARN[020]):
   same commit, same profile, but a fresh clone (a different surefire order) failed 2 tests
   that the main repo's order hid. Two genuine defects: (a) `RenovatorMetrics` registered its
   meters **lazily** inside `observe()`, so a `fixture-clean` run (never enters `Repairing`)
   left `renovator_replans_total` absent from the scrape — `PrometheusMetricsIT` failed in
   isolation and only "passed" in the full suite when a prior test pre-registered it. Fix:
   pre-register all meters + timer at `attach()` (LEARN[019]). (b) `RunService` ran the agent
   on a single-thread **daemon executor that was never drained**; a test that submitted a run
   and returned before it finished left the worker thread executing into the *next* test class,
   where — via the global `LlmChannel.actions`/`AgentTrace`/`RunAudit` — it drained the next
   test's `ScriptedLlm` queue or invoked the real `LlmActions`. The clone orders a RunService
   test before `TwoHopReplanIT`; main does not, so main hid it. Fix: `RunService` is
   `AutoCloseable`; `close()` terminates live processes + shuts down the executor; the
   RunService-using tests `close()` in a `finally` (`RunServiceIT`, `ApprovalGateIT`,
   `KillResumeIT`); `RunServiceIT.test2` now retries the single-run *gate* instead of polling
   the `Done` stage (which is visible before the executor's `finally` frees the slot)
   (LEARN[020]).

Clone transcript (verbatim, the reproduction below, from HEAD `3ce9e1e`):

<!-- CLONE_TRANSCRIPT -->

```
$ git clone file://$PWD /tmp/renovator-clone && cd /tmp/renovator-clone
Cloning into '/tmp/renovator-clone'...
3ce9e1e phase-6.remediation: make the eval/docker gate hermetic (order-independent)

$ ./mvnw verify
[INFO] Tests run: 115, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

$ ./mvnw -Peval-mock,docker-it verify
[INFO] Tests run: 149, Failures: 0, Errors: 0, Skipped: 3
[INFO] BUILD SUCCESS

$ scripts/demo-replan.sh      [8] BUILD OBSERVED: FAILED ['[maven-enforcer-plugin:enforce]']  [18] BUILD OBSERVED: GREEN []  (REPLAN_OK)
$ scripts/demo-kill-resume.sh  RESUMED run kill-demo -> UpgradeComplete  (KILLRESUME_OK)
```

## Task 7.4 — Final report + acknowledgements

- Every prior phase report is present (`docs/phase-reports/phase-0.md … phase-6.md` + `final-audit.md`).
- README closing (bounded-claim) paragraph present verbatim-intent.

## Model attribution (final)

**Executor** (the agent producing this work):

| Range | Executor model | Note |
|---|---|---|
| Phase 0 – Phase 5 impl + phase-4 reports | deepseek-v4-flash | the original executor |
| phase-5.gate report + Phase 6 impl + first remediation | Kimi K3 | switched at the Phase 5/6 boundary (quality) |
| phase-6 grounded-prompt fix + Phase 7 docs + hermeticity remediation (`3ce9e1e`) + this audit | DeepSeek | resolve; **Moonshot-balance interruption** recorded |

**Runtime eval** (Renovator's LLM via `LLM_MODEL`): `gpt-4.1-mini` (default; the live-eval
**baseline**, floor FAIL — recorded untouched in `eval/reports/2026-09-02-live-mini.md`);
`gpt-4.1` (**the live floor pin**, floor PASS after the grounded-prompt fix —
`eval/reports/2026-09-02-live.md`); `ollama` (local option, KL-06).

## Gate 7 evidence

| Check | Result |
|---|---|
| `./mvnw verify` (main repo) | **`Tests run: 115, Failures: 0, Errors: 0, Skipped: 0`** — BUILD SUCCESS |
| `./mvnw -Peval-mock,docker-it verify` (main repo) | **`Tests run: 149, Failures: 0, Errors: 0, Skipped: 3`** — BUILD SUCCESS |
| clone reproduction (7.3) | see `final-audit.md` — all green (115/115, 149/0/0/3, both demos) |
| `python3 scripts/check_protocols.py --full` | **`0 violations`** |
| `git status --porcelain` | empty |
| tags | `phase-0-complete` … `phase-7-complete` |

## Final log (closing artifact)

LEARN[020] (quoted in full; the reviewer's standing order: one LEARN in full + the restate):

> **LEARN[020] Drain the async executor: a test-created RunService must close()**
> **Why this way:** the agent runs on a single-thread DAEMON executor that is never
>   naturally stopped. A test that submits a run and returns before that run fully drains
>   leaves its worker thread executing into the NEXT test class — and because
>   LlmChannel.actions, AgentTrace and RunAudit are process-global singletons, that
>   still-running thread is swapped to whatever the next test sets: it drains the next test's
>   ScriptedLlm plan queue (so the next run gets the wrong plan or an empty queue) or, once
>   the next test resets the channel to the real LlmActions, it makes real LLM calls that fail
>   (InvocationTargetException) and replan forever. That is a genuinely order-dependent failure
>   — a fresh-clone reproduction exposed it because the clone's surefire order runs a
>   RunService test before TwoHopReplanIT, while the main repo's order runs TwoHopReplanIT
>   first and hides it. The fix: every test that constructs a RunService closes it in a
>   finally, and close() terminates any still-live process (unblocking the worker) and shuts
>   the executor down, so no agent thread survives into a later test class.
> **Good sides:** a hermetic, order-independent suite (the gate is reproducible); production
>   (the Spring @Component) is unaffected — there is no "next test" to corrupt, and the
>   executor lives for the JVM lifetime as intended.
> **Drawbacks:** close() interrupts an in-flight run, so a test must assert whatever it needs
>   before finally — which the tests already do (they poll to Done).
> **Concept:** drain-or-leak — an async executor that nothing shuts down is a thread that can
>   outlive its test.
> **See also:** LEARN[015] (the RunService observable loop), PLAN Task 5.1, RunServiceIT,
>   ApprovalGateIT, KillResumeIT

---

<!-- FINAL_GIT_LOG -->

*(Final `git log --oneline` — 60 commits; tags `phase-0-complete`…`phase-6-complete` point at
their gate/remediation commits, and the new `phase-7-complete` at the Phase-7 gate. See the
identical list in `final-audit.md`.)*

## Note on the "16 essays" (plan staleness)

Task 7.2's "every number 001–016" is stale — the live LEARN set is `001–020` after
LEARN[017] (retry taxonomy), LEARN[018] (placeholder-echo), LEARN[019] (register metrics
eagerly) and LEARN[020] (drain the async executor). The audit checks the actual set (all
present, ascending, one location each), which is complete.

## Addendum — randomized-order reproducibility evidence (reviewer strengthener)

After the Phase-7 gate, the reviewer accepted the hermeticity fix but noted the stated bar
called for a randomized-order run. This is the strengthener, added **evidence-only**: no test,
source, or gate configuration was changed (git diff is confined to this report).

Command (fresh clone from HEAD `a40080a`, run in `/tmp/renovator-clone4`):

```
./mvnw -Peval-mock,docker-it -Dsurefire.runOrder=random -Dsurefire.runOrder.random.seed=42 verify
```

Surefire confirms the ordering is genuinely shuffled (verbatim from the log), not a default-order
run that merely accepted the property:

```
[INFO] Tests will run in random order. To reproduce ordering use flag -Dsurefire.runOrder.random.seed=42
```

Result (verbatim):

```
[INFO] Tests run: 149, Failures: 0, Errors: 0, Skipped: 3
[INFO] BUILD SUCCESS
```

54 of the 57 run classes changed position relative to the default filesystem order (first
class default `com.renovator.domain.ResultTypesTest`, first class random
`com.renovator.validation.DomainInvariantValidatorTest`) — the run is genuinely shuffled, not
a default-order run that accepted the property. The two tests the original clone reproduction
failed on — `PrometheusMetricsIT` (lazy meter registration; default position 21 → random
position 38) and `TwoHopReplanIT` (undrained async executor; default position 35 → random
position 40) — both ran **and passed** under random order. This demonstrates the hermeticity
fix is order-independent: it is stable under an arbitrary, seed-reproducible test ordering,
not merely under the single default order or the specific adverse clone order that exposed
it. Combined with the serial main + clone runs, the gate is reproducibly green.

