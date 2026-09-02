# Phase 7 report — Docs & final audit

- Date: 2026-09-02; Executor: dsh (DeepSeek — see Model attribution); Branch state at gate: `06c4710` + the final-audit/phase-7 commits (clean tree at gate)
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
(`001–018`, ascending, one location each); KNOWN_LIMITATIONS 1:1 both directions (every
`TODO(review) KL-NN` ↔ exactly one row; ascending; KL-12 struck/closed, KL-13 live);
tags `phase-0-complete` … `phase-6-complete` present and pointing at gate commits.

## Task 7.3 — Clean-clone reproduction

`git clone file://$PWD /tmp/renovator-clone && cd /tmp/renovator-clone && ./mvnw verify &&
./mvnw -Peval-mock,docker-it verify && scripts/demo-replan.sh && scripts/demo-kill-resume.sh`
(all on the WSL2-native fs, D15). **Two authenticity defects caught + fixed by this pass:**

1. `src/test/resources/buildlogs/*.log` (two fixture logs read by `CompileErrorParserTest` /
   `BuildResultParserTest`) were **gitignored and never committed** → a fresh clone NPE'd.
   Fixed in `f67a1c2` (force-added the fixtures + a `.gitignore` negation).
2. `ReadmeStructureTest` had a **ktlint violation** that the main repo's pre-7.1 verify never
   ran → the clone's verify failed. Fixed in `06c4710` (ktlint:format).

Clone transcript (verbatim, the reproduction below):

<!-- CLONE_TRANSCRIPT -->

## Task 7.4 — Final report + acknowledgements

- Every prior phase report is present (`docs/phase-reports/phase-0.md … phase-6.md` + `final-audit.md`).
- README closing (bounded-claim) paragraph present verbatim-intent.

## Model attribution (final)

**Executor** (the agent producing this work):

| Range | Executor model | Note |
|---|---|---|
| Phase 0 – Phase 5 impl + phase-4 reports | deepseek-v4-flash | the original executor |
| phase-5.gate report + Phase 6 impl + first remediation | Kimi K3 | switched at the Phase 5/6 boundary (quality) |
| phase-6 grounded-prompt fix + Phase 7 | DeepSeek | resolve; **Moonshot-balance interruption** recorded |

**Runtime eval** (Renovator's LLM via `LLM_MODEL`): `gpt-4.1-mini` (default; the live-eval
**baseline**, floor FAIL — recorded untouched in `eval/reports/2026-09-02-live-mini.md`);
`gpt-4.1` (**the live floor pin**, floor PASS after the grounded-prompt fix —
`eval/reports/2026-09-02-live.md`); `ollama` (local option, KL-06).

## Gate 7 evidence

| Check | Result |
|---|---|
| `./mvnw verify` (main repo) | **`Tests run: 115, Failures: 0, Errors: 0, Skipped: 0`** — BUILD SUCCESS |
| `./mvnw -Peval-mock,docker-it verify` (main repo) | **`Tests run: 146, Failures: 0, Errors: 0, Skipped: 3`** — BUILD SUCCESS |
| clone reproduction (7.3) | see `final-audit.md` — all green |
| `python3 scripts/check_protocols.py --full` | **`0 violations`** |
| `git status --porcelain` | empty |
| tags | `phase-0-complete` … `phase-7-complete` |

## Final log (closing artifact)

<!-- FINAL_GIT_LOG -->

## Note on the "16 essays" (plan staleness)

Task 7.2's "every number 001–016" is stale — the live LEARN set is `001–018` after
LEARN[017] (retry taxonomy) and LEARN[018] (placeholder-echo). The audit checks the actual
set (all present, ascending, one location each), which is complete.
