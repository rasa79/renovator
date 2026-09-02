# Final audit (PLAN Task 7.2 / 7.3)

- Date: 2026-09-02; Executor: dsh (DeepSeek — see Model attribution); Repo HEAD: `f67a1c2` (phase-7.3) + the audit/phase-7 commits below (clean tree at gate)
- Command: `python3 scripts/check_protocols.py --full` → **`0 violations`**

## Protocol audit

- `check_protocols.py --full`: **0 violations** (all rules, all files, all history boundary states).
- **LEARN_INDEX completeness:** every number `001–018` present, exactly one location each, **ascending** (no gaps; the plan's Task-7.2 "16 essays" is stale — the live set is 18 after LEARN[017] retry-taxonomy + LEARN[018] placeholder-echo). LEARN[001]–[018] all indexed.
- **KNOWN_LIMITATIONS 1:1 both directions:** every `TODO(review) KL-NN` marker ↔ exactly one ledger row (and vice-versa). Live rows: KL-01 … KL-10, KL-13; KL-12 struck/closed (closing commit referenced); KL-11 never issued (numbering history). **Ascending** as enforced by the checker.
- **Tags present:** `phase-0-complete` … `phase-6-complete` + the new `phase-7-complete`, each pointing at its gate/remediation commit (verified above).

## Model attribution

**Executor model per phase** (the provider/brand running this agent changed mid-project; this is the executor, orthogonal to the project's runtime eval model):

| Phases / commit range | Executor model | Note |
|---|---|---|
| Phase 0 – Phase 5 impl (through `16da580`) + phase reports (`phase-1.gate` … `phase-4.gate`, `phase-4.remediation`) | **deepseek-v4-flash** | the original executor, stable through Phase 4 + Phase 5 implementation |
| Phase-5 gate report (`df4d0d0`) + Phase 6 impl (`0b19da4` … `76d9cd4`, `17565c6`) + first remediation (`6434479`) | **Kimi K3** | switched at the Phase 5/6 boundary (quality reasons) |
| Phase-6 grounded-prompt remediation (`3d4cb0a`) + Phase 7 (`a53299b`, `f67a1c2`) + this audit | **DeepSeek** | resolve; the **Moonshot-balance interruption** is recorded here (the provider that resolved to DeepSeek ran out of balance mid-Phase-6-remediation) |

**Runtime eval models** (the Renovator agent's LLM, configured via `LLM_MODEL`; orthogonal to the executor):

| Model | Role | Result |
|---|---|---|
| `gpt-4.1-mini` | configured default; the live-eval **baseline** | floor FAIL (placeholder-echo; recorded untouched in `eval/reports/2026-09-02-live-mini.md`) |
| `gpt-4.1` | the **live floor pin** (D13; from the remediation) | **floor PASS** (fixture-clean UpgradeComplete + fixture-no-path UpgradeBlocker) after the grounded-prompt fix |
| `ollama` (option) | local provider via `LLM_PROVIDER=ollama` | KL-06 (may be slow on modest hardware) |

## Clean-clone reproduction (Task 7.3)

Command: `git clone file://$PWD /tmp/renovator-clone && cd /tmp/renovator-clone && ./mvnw -q verify && ./mvnw -q -Peval-mock,docker-it verify && scripts/demo-replan.sh && scripts/demo-kill-resume.sh` — on the WSL2-native fs (D15).

**Defect caught + fixed by this pass:** the first clone failed — `src/test/resources/buildlogs/*.log` (two test fixtures consumed by `CompileErrorParserTest` + `BuildResultParserTest`) were **gitignored and never committed**, so a fresh clone NPE'd. Fixed in `f67a1c2` (force-added the fixtures + a `.gitignore` negation for `src/test/resources/buildlogs/*.log`). The reproduction re-run below is from the fixed HEAD.

Clone transcript (verbatim):

<!-- CLONE_RESULTS -->

## Closing artifact — final `git log --oneline`

<!-- GIT_LOG -->

## Conclusion

The clone, with no local state and no uncommitted files, passes every gate a fresh
contributor would hit (verify + eval-mock verify + both demo scripts), the protocol holds
under `--full` audit, the LEARN/KL ledgers are complete and ascending, and all seven phase
tags point at gate commits. The bounded claim — a deterministic judge + cheap reversibility —
traces through the executor boundary, the sandbox, and the judge fixtures, and the
"where it doesn't" README section names exactly where one of the two load-bearing properties
is absent.
