# Renovator working protocol (PLAN.md §10, rendered)

This repository's process rules. The mechanical subset is enforced by
`scripts/check_protocols.py`, installed as a pre-commit hook by
`scripts/install_hooks.sh` (PLAN §10.4). Load-bearing: the hook failing blocks the
commit; `--no-verify` is forbidden (GW-4).

## 1. LEARN comments (§10.1)

A comment teaching something the code cannot show. Five fields:

```text
// LEARN[NNN] Short title
// Why this way: ...
// Good sides: ...
// Drawbacks: ...
// Concept: ...
// See also: LEARN[MMM], docs/...   (optional)
```

- Global zero-padded numbers (001, 002, …), never reused. Deleted comments stay in
  `LEARN_INDEX.md` as struck-through rows.
- `LEARN_INDEX.md` holds one live row per number: `| NNN | title | file:line | concept |`;
  the checker recomputes `file:line` from the code and compares.
- Cross-reference with `See also:`; a labelled stub (`LEARN-REF[NNN] → see
  LEARN[NNN]`) is the only permitted short form.
- **Restate test:** if a competent Java/Spring engineer new to Kotlin and agentic
  planning could reconstruct the comment from the code alone, it fails. Floor ≈ 6 lines.
- Audience: Java/Spring engineers new to Kotlin and agentic planning — anchor:
  data class ≈ record, sealed class ≈ sealed interface, `WaitFor` ≈ BPMN human task.

## 2. Deferred work (§10.2)

```text
// TODO(review) KL-NN  (in code, with the id)
```

- Every `TODO(review) KL-NN` marker ↔ exactly one `KNOWN_LIMITATIONS.md` row, 1:1,
  both directions; the checker fails an orphan on either side.
- Row format: `| KL-NN | title | user-visible: yes|no | pre-declared: yes|no | rationale |`.
  `pre-declared: yes` rows are the seeded scope/design limitations from PLAN §10.2
  (KL-01…KL-06) — they have no code marker by construction.
- Struck-through rows are closed with a commit reference in the rationale.
- `user-visible: yes` rows each need one README sentence (checked by the audit from
  Phase 7 on).
- `TODO(`…`)` is only ever `TODO(review)` (QS-3); no placeholders.

## 3. Git clauses (§10.3, GW-1…GW-4)

- One commit per task: `phase-N.M: <task title>`. No mixed-task commits, no `wip`.
- Annotated tag `phase-N-complete` only after the phase gate passes; the tag message
  carries gate evidence.
- `git status --porcelain` empty at every phase boundary and before every tag.
- Pre-commit hook runs on every commit; `--no-verify` is forbidden. The phase report
  lists every commit hash + tag + one hook-output line per commit as attestation.

## 4. The checker (`scripts/check_protocols.py`, Appendix C)

Stdlib-only Python 3; exit 0 = clean, exit 1 = one line per violation. Rules:

1. **LEARN format.** Blocks over `src/**`, `fixtures/**`, `scripts/**`, `docs/**`
   (excluded: `LEARN_INDEX.md`, phase reports, and `scripts/test_check_protocols.py` —
   its fixtures contain literal LEARN blocks as test data). Each block: the five
   fields, ≥ 6 lines, unless a `LEARN-REF` stub (exempt; must reference an existing
   number).
2. **Numbering & index.** No duplicates; `LEARN_INDEX.md` exactly one live row per
   number with matching title and recomputed `file:line`. PLAN §10.1.8 pre-allocates
   slots 001–016 whose placements land out of sequence across tasks (e.g. LEARN[006]
   is Task 2.7, after LEARN[007] in Task 2.3), so transient gaps inside 1–16 are
   permitted; numbers beyond 16 must be gapless; `--full` audits 1–16 completeness.
3. **Deferred work 1:1.** `TODO(review) KL-NN` ↔ `KNOWN_LIMITATIONS.md` rows, both
   directions; pre-declared rows exempt from the marker side; README sentence
   requirement with `--full` or from Phase 7.
4. **Prompt location.** From Phase 3 on, prompt-shaped literals (triple-quoted
   strings ≥ 3 lines containing instruction verbs) outside `src/main/resources/prompts/`
   fail; before Phase 3 the rule is advisory (off).
5. **Phase boundary.** With `--phase-boundary`: `git status --porcelain` empty and
   the newest `phase-N-complete` tag reachable from HEAD. Run at phase gates — the
   pre-commit hook deliberately skips this rule (the index is necessarily dirty
   while committing).

CLI: `python3 scripts/check_protocols.py [--phase-boundary] [--full] [--force-phase N] [--verbose]`.
Self-tests: `python3 scripts/test_check_protocols.py` (covers every rule + and −).

## 5. Execution rules (§10.5) and quality standards (§10.6)

- No silent scope cuts or design decisions: deviation → environment note in the
  phase report + (if durable) a KNOWN_LIMITATIONS entry.
- The planner is dynamic; the palette, preconditions, costs, and guards are
  hand-declared and reflection-tested (`AgentPaletteCompletenessTest`).
- Prompts live in one versioned location (`src/main/resources/prompts/*.st`); editing
  a prompt is a commit of its own (`phase-N.M: prompt: <name>`).
- Unit tests run in `./mvnw verify`; Docker-dependent and live-LLM tests are `*IT`
  behind profiles `docker-it` / `llm-it`. Nothing is `@Disabled` without a KL entry.
- Every task lands with its named tests green in the same commit (QS-1); every
  Demonstration line is executed verbatim and pasted into the phase report (QS-2);
  comments / LEARN blocks / index / KNOWN_LIMITATIONS travel in the same commit as
  the code they describe (QS-4); a phase is done only when its gate block is complete
  (QS-5).
