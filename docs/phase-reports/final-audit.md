# Final audit (PLAN Task 7.2 / 7.3)

- Date: 2026-09-02; Executor: dsh (DeepSeek — see Model attribution); Repo HEAD: `3ce9e1e` (phase-6.remediation: hermetic gate) + the audit/phase-7 reports below (clean tree at gate)
- Command: `python3 scripts/check_protocols.py --full` → **`0 violations`**

## Protocol audit

- `check_protocols.py --full`: **0 violations** (all rules, all files, all history boundary states).
- **LEARN_INDEX completeness:** every number `001–020` present, exactly one location each, **ascending** (no gaps; the plan's Task-7.2 "16 essays" is stale — the live set is 20 after LEARN[017] retry-taxonomy + LEARN[018] placeholder-echo + LEARN[019] eager-metrics + LEARN[020] drain-or-leak). LEARN[001]–[020] all indexed.
- **KNOWN_LIMITATIONS 1:1 both directions:** every `TODO(review) KL-NN` marker ↔ exactly one ledger row (and vice-versa). Live rows: KL-01 … KL-10, KL-13; KL-12 struck/closed (closing commit referenced); KL-11 never issued (numbering history). **Ascending** as enforced by the checker.
- **Tags present:** `phase-0-complete` … `phase-6-complete` + the new `phase-7-complete`, each pointing at its gate/remediation commit (verified above).

## Model attribution

**Executor model per phase** (the provider/brand running this agent changed mid-project; this is the executor, orthogonal to the project's runtime eval model):

| Phases / commit range | Executor model | Note |
|---|---|---|
| Phase 0 – Phase 5 impl (through `16da580`) + phase reports (`phase-1.gate` … `phase-4.gate`, `phase-4.remediation`) | **deepseek-v4-flash** | the original executor, stable through Phase 4 + Phase 5 implementation |
| Phase-5 gate report (`df4d0d0`) + Phase 6 impl (`0b19da4` … `76d9cd4`, `17565c6`) + first remediation (`6434479`) | **Kimi K3** | switched at the Phase 5/6 boundary (quality reasons) |
| Phase-6 grounded-prompt remediation (`3d4cb0a`) + Phase 7 docs + hermeticity remediation (`3ce9e1e`) + this audit | **DeepSeek** | resolve; the **Moonshot-balance interruption** is recorded here (the provider that resolved to DeepSeek ran out of balance mid-Phase-6-remediation) |

**Runtime eval models** (the Renovator agent's LLM, configured via `LLM_MODEL`; orthogonal to the executor):

| Model | Role | Result |
|---|---|---|
| `gpt-4.1-mini` | configured default; the live-eval **baseline** | floor FAIL (placeholder-echo; recorded untouched in `eval/reports/2026-09-02-live-mini.md`) |
| `gpt-4.1` | the **live floor pin** (D13; from the remediation) | **floor PASS** (fixture-clean UpgradeComplete + fixture-no-path UpgradeBlocker) after the grounded-prompt fix |
| `ollama` (option) | local provider via `LLM_PROVIDER=ollama` | KL-06 (may be slow on modest hardware) |

## Clean-clone reproduction (Task 7.3)

Command: `git clone file://$PWD /tmp/renovator-clone && cd /tmp/renovator-clone && ./mvnw verify && ./mvnw -Peval-mock,docker-it verify && scripts/demo-replan.sh && scripts/demo-kill-resume.sh` — on the WSL2-native fs (D15), run **serially** (no concurrent builds).

**Defects caught + fixed by this pass (three):**

1. **Fixture logs not committed** (`f67a1c2`): `src/test/resources/buildlogs/*.log` (two test fixtures consumed by `CompileErrorParserTest` + `BuildResultParserTest`) were **gitignored and never committed**, so a fresh clone NPE'd. Fixed by force-adding the fixtures + a `.gitignore` negation.
2. **ktlint violation not caught in the sub-repo** (`06c4710`): `ReadmeStructureTest` had a ktlint violation that the main repo's pre-7.1 `verify` never ran; the clone's `verify` failed. Fixed via `ktlint:format`.
3. **The eval/docker gate was order- and state-dependent, not reproducibly green** (`3ce9e1e`, LEARN[019] + LEARN[020]): same commit, same profile, but a fresh clone (different surefire order) failed 2 tests that the main repo's order hid. Root causes: (a) `RenovatorMetrics` registered its meters **lazily inside `observe()`**, so a `fixture-clean` run (never enters `Repairing`) left `renovator_replans_total` absent from the scrape — `PrometheusMetricsIT` failed in isolation and only "passed" in the full suite when a prior test pre-registered the counter. Fix: pre-register all meters + timer at `attach()`. (b) `RunService` ran the agent on a single-thread **daemon executor that was never drained**; a test that submitted a run and returned before it finished left the worker thread executing into the *next* test class, where (via the global `LlmChannel.actions`/`AgentTrace`/`RunAudit`) it drained the next test's `ScriptedLlm` queue or invoked the real `LlmActions` — the clone orders a RunService test before `TwoHopReplanIT`, main does not. Fix: `RunService` is `AutoCloseable`; `close()` terminates live processes + shuts down the executor; the RunService-using tests `close()` in a `finally` (`RunServiceIT`, `ApprovalGateIT`, `KillResumeIT`). `RunServiceIT.test2` now retries the single-run *gate* instead of polling the `Done` stage.

Clone transcript (verbatim, from HEAD `3ce9e1e`):

<!-- CLONE_RESULTS -->

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

$ scripts/demo-replan.sh
  [8] BUILD OBSERVED: FAILED ['[maven-enforcer-plugin:enforce]']
  [18] BUILD OBSERVED: GREEN []
  (output contains "single direct bump" and "pin the transitive guava, then bump the direct dependency")
REPLAN_OK

$ scripts/demo-kill-resume.sh
RESUMED run kill-demo -> UpgradeComplete
KILLRESUME_OK
```

## Closing artifact — final `git log --oneline`

<!-- GIT_LOG -->

```
3ce9e1e phase-6.remediation: make the eval/docker gate hermetic (order-independent)
06c4710 phase-7.1: ktlint-format ReadmeStructureTest (clone reproduction caught the un-checked violation)
f67a1c2 phase-7.3: commit the test-fixture build logs (untracked/ignored -> fresh-clone NPE; caught by the clone reproduction)
a53299b phase-7.1: README per Appendix A (verbatim applicability + clone-and-reproduce) + ReadmeStructureTest; KL-13 (live repair-model prompt finding)
3d4cb0a phase-6.remediation: ground the live proposal prompt (D13 floor passes under gpt-4.1); LEARN[018]; model pin
6434479 phase-6.remediation: gpt-4.1 live floor comparison (floor not met; diagnosis = prompt/harness) — STOPPED for human decision
17565c6 phase-6.gate: phase-6 report (mock 4/4, live-eval measured, metrics, LEARN[016], model-switch note)
76d9cd4 phase-6.x: ktlint format + mock eval report
e24b91e phase-6.3: metrics emit hook in RunAudit
1c8f881 phase-6.4b: README (clone-and-reproduce)
66e3bb8 phase-6.4: Trajectory query (type+stage), audit-trail doc, LEARN[016]
9679d76 phase-6.3: Micrometer metrics (RenovatorMetrics + MetricsIT + prometheus)
774a35a phase-6.2: Eval harness, live mode (LiveEvalIT, gated; floor = measurement)
0b19da4 phase-6.1: Eval harness, mock mode (EvalRunner + MockEvalIT 4/4, eval-mock profile)
df4d0d0 phase-5.gate: phase-5 report (C-6 verdict, KL-09 permanent, live-smoke, CLI)
16da580 phase-5.4: Minimal CLI (scripts/renovator) + CliSmokeIT
99561d5 phase-5.3: HITL approval gates via WaitFor (C-6 fallback, KL-09 PERMANENT, LEARN[015])
962ded2 phase-5.2: SSE replay-then-tail (SseController + TrajectoryBus)
098784d phase-5.1: REST control API (RunController, async RunService, KL-01 409, KL-03 validation)
f3e73ae phase-4.remediation: report gate-header refresh
11877de phase-4.remediation: real SIGKILL demo, lane-flip postmortem, KL-08 re-seed
71bbc1b phase-4.gate: phase-4 report
7ec3232 phase-4.5: Kill-and-resume mid-upgrade (D10, C-5)
4e03589 phase-4.4: Honest termination on fixture-no-path (bounded planner, C-7)
69d4107 phase-4.3: Two-hop replanning demo on fixture-transitive-conflict
082f436 phase-4.2: Repair loop on fixture-api-removal
21e79e4 phase-4.1: State hierarchy wired (@State, C-2)
6700e90 phase-3.9: enforce ascending row order in ledgers
11ba405 phase-3.gate: phase-3 report
4f6ad7f phase-3.5: Prompt/version hygiene checkpoint
53f9fe5 phase-3.4: Happy path end-to-end on fixture-clean (mock LLM)
a62e65f phase-3.3: Action costs & preconditions (D9, C-4)
41b23db phase-3.2: LLM actions with typed binding (D6, C-1)
e06ae01 phase-3.1: Agent shell + deterministic actions
e8d1115 phase-2.9: cosmetic row ordering in ledgers
c342adc phase-2.gate: phase-2 report
ac0018c phase-2.7: Executor boundary + signature test
8490dcc phase-2.6: Layer 4 DryRunCompileValidator
465130b phase-2.5: Layer 3 DomainInvariantValidator + VersionCatalog
d00141d phase-2.4: Layer 2 DiffApplyValidator
b430d81 phase-2.3: Layer 1 PathWhitelistValidator
2f356a2 phase-2.2: Result types, Excerpt reuse, stage hierarchy (pre-State)
9a61df7 phase-2.1: Proposal types + strict Jackson boundary
f4b8f79 phase-2.no-verify-guard: mechanical GW-4 guard (reviewer standing condition)
915bd48 phase-1.gate: phase-1 report
27f48e5 phase-1.6: Sandbox build runner (D7)
bc8ead1 phase-1.5: Outcome schema + fixtures README (eval dataset, D13)
59e1f11 phase-1.4: fixture-no-path
65540ae phase-1.3: fixture-transitive-conflict
dd00a7a phase-1.2: fixture-api-removal
f4f5692 phase-1.1: fixture-clean
45db048 phase-1.kl-12: LLM retry semantics on non-retryable quota errors (KL-12, reviewer carry-forward)
5cc6fd2 phase-0.gate: phase-0 report
e8f648d phase-0.6: Protocol tooling (checker, hook, index, limitations seeds)
9775eae phase-0.5: Dual-provider LLM smoke test
0c82c80 phase-0.4: Config system (dual LLM provider, sandbox, validation rules)
311cbf0 phase-0.3: Embabel capability re-verification + minimal agent shell
f537d20 phase-0.2: ktlint, pinned and bound to verify
f009acd phase-0.1: Maven/Kotlin/Spring Boot skeleton
902b7f7 phase-0.0: Materialize plan, repo init, executor pre-flight
```

(60 commits; tags `phase-0-complete`…`phase-6-complete` point at their gate/remediation commits, and the new `phase-7-complete` at the Phase-7 gate.)

**Reproducibility note:** the `-Peval-mock,docker-it verify` gate shares one JVM across all
test classes (surefire default `forkCount=1`, `reuseForks=true`), and several tests use
process-global singletons (`LlmChannel.actions`, `RunAudit.runId`, `AgentTrace`). Before the
hermeticity fix (`3ce9e1e`) this made the gate order-dependent. After the fix the gate is
**reproducibly green**, with one operational caveat: run it with no concurrent Maven build on
the same tree (a stray concurrent `mvn` on the same `target/` can still cause a one-off,
unrelated flake — one such artifact was observed and confirmed not to reproduce on a clean
sequential run). The authoritative evidence above and in `phase-7.md` is from **serial** runs
with nothing else building.

The clone, with no local state and no uncommitted files, passes every gate a fresh
contributor would hit (verify 115/115 + eval-mock/docker 149/0/0/3 + both demo scripts),
the protocol holds under `--full` audit, the LEARN/KL ledgers are complete and ascending,
and all seven phase tags point at gate commits. The bounded claim — a deterministic judge +
cheap reversibility — traces through the executor boundary, the sandbox, and the judge
fixtures, and the "where it doesn't" README section names exactly where one of the two
load-bearing properties is absent.
