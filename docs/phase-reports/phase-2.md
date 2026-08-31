# Phase 2 report — Domain model & validation pipeline

- Date: 2026-08-31; Executor: dsh Standard; Branch state at gate: `ac0018c` (clean tree)
- dsh trajectory: `session-5db7b162-2830-4c70-9640-59fb4f8ce511` (checkpoints by UTC timestamp)

## Gate evidence

| Gate command | Result | dsh trajectory checkpoint |
|---|---|---|
| `./mvnw verify` | **`[INFO] Tests run: 66, Failures: 0, Errors: 0, Skipped: 0`** … `BUILD SUCCESS` (ktlint gate green) | 2026-08-31T12:39:00Z |
| `./mvnw -Pdocker-it verify` | **`[INFO] Tests run: 75, Failures: 0, Errors: 0, Skipped: 1`** … `BUILD SUCCESS` (Skipped: 1 = LlmConnectivitySmokeIT, gated by LLM_SMOKE) | 2026-08-31T12:41:20Z |
| `python3 scripts/check_protocols.py --phase-boundary` | `0 violations` | 2026-08-31T12:42:05Z |
| `git status --porcelain` | empty | 2026-08-31T12:42:05Z |

## The Validated* border: type-system enforcement, proven (reviewer mandate)

**Mechanism (what PLAN.md fixes, realized):** `ValidatedPatch`/`ValidatedPlan` are FINAL classes with PRIVATE primary constructors; their only callers are the same-file factories in `validation/Validated.kt`, which enforce the mandatory layer set (L1, L2, L3) and bind `sha256(canonical JSON payload)` into the proof. `UpgradeExecutor` recomputes the digest and re-checks the mandatory layers at apply time. Kotlin nuance documented in the code: PLAN says "sealed"; Kotlin sealed classes are implicitly abstract and cannot be instantiated even by their own companion, so the equivalent enforcement is private-ctor + final + factory (the fork route is foreclosed by `final`; the direct route does not compile).

**The bypass does not compile — quoted compiler error (probe file, not committed):**
```
[ERROR] file:///…/execution/BypassProbe.kt:13:16 Cannot access 'constructor(patch: CodePatch,
        proof: ValidationProof): ValidatedPatch': it is private in 'com.renovator.validation.ValidatedPatch'.
```
A caller outside the validation package cannot construct a `ValidatedPatch` — the type system, not a convention, is the barrier.

**And even a constructed one is refused** (ExecutorBoundaryTest, all green):
- `rejects raw CodePatch JSON POSTed as ValidatedPatch` — Jackson cannot deserialize into a private-ctor class; `MismatchedInputException` (no public/value ctor).
- `rejects forged proof whose digest does not match the payload` — reflection-built instance, wrong digest → `UnvalidatedProposalException` naming the digest mismatch.
- `rejects proof whose checkNames omit mandatory layers` — `ValidatedPatch.create(..., listOf("L2:only"))` throws at CREATION: "proof must be backed by all mandatory layers, missing: [L1, L3]".
- `rejects a ValidatedPatch constructed by reflection with a garbage proof` — KL-07 documented honestly: reflection can construct in-process; the digest check still refuses; the boundary defends the LLM/planner path, not malicious in-process code.
- `every public method of UpgradeExecutor declares only Validated-star parameter types` — reflection over the API surface: a raw `CodePatch`/`UpgradePlan` parameter would fail the build.

**Signature test where it belongs** — the executor applying a *genuinely* validated plan to a workspace copy (`UpgradeExecutorTest.applies a genuinely validated plan to a workspace copy`): `ValidatedPlan.create(...)` (mandatory layers + digest) → `UpgradeExecutor().apply(...)` → the copy's pom pins `3.14.0` (was `3.12.0`), receipt returned, source tree untouched.

## Pipeline proven red (reviewer mandate) — LLM-shaped but invalid output, rejected with reason

Verbatim rejection reasons, surfaced typed (`ValidationRejection(checkName, reason, offendingContent)`):

- **L1** — `checkName=L1:absolute-or-escaping`, reason `path 'src/main/kotlin/../../secrets/token.txt' is not permitted (normalized: <absolute>)` (dot-dot escape); `L1:forbidden:.git/**` beats a wildcard allow; `L1:not-whitelisted` for `RunMe.sh`/`.env*` at any depth. Property test: 1000 random separator/dot-segment mixes, any normalized path matching a forbidden pattern is rejected.
- **L2** — context drift: `checkName=L2:hunk-1`, reason `expected context line 'int z;' not found at line 6`; scope: `L2:binary-by-scope`, `L2:rename-only-by-scope`, `L2:deletion-by-scope`, `L2:path-mismatch`. Property test: 1000 generated diffs with corrupted context — none applies.
- **L3** — `L3:version-exists` (`commons-lang3:99.99.99 does not exist`), `L3:monotonic` (downgrade rejected even when the version exists), `L3:snapshots`, `L3:pom-parse`/`L3:model-version`/`L3:repository-allowlist` (`repository 'evil' (https://evil.example/maven2) is outside the allowlist` — the supply-chain guard).
- **L4** — sandboxed dry-run compile of the swapped api-removal fixture: typed `CompileError(file=EscapeSqlFormatter.java, line=17, col=16, message="cannot find symbol")` (see Task 2.6 IT; the L4 RED run is also the judge-says-red demonstration from the Phase-1 tradition).

## Demonstration outputs (QS-2, verbatim key lines)

- **2.1** `./mvnw -q test -Dtest=ProposalTypesTest && echo DOMAIN-OK` → `4 tests, 0 failures` (strict mapper rejects `hallucinatedField`; sealed `PlanStep` serializes with `"type":"VersionStep"`). Stub removal verified: `grep -rn UpgradeGoalStub src/main` → no matches.
- **2.2** `./mvnw -q test -Dtest='ResultTypesTest,StageHierarchyTest' && echo RESULTS-OK` → 4 tests, 0 failures.
- **2.3** `./mvnw -q test -Dtest='PathWhitelistValidatorTest,PathWhitelistPropertyTest' && echo L1-OK` → `L1-OK` (8 + 1000-try property).
- **2.4** `./mvnw -q test -Dtest='DiffApplyValidatorTest,DiffApplyPropertyTest' && echo L2-OK` → `L2-OK` (7 + 1000-try property).
- **2.5** `./mvnw -q test -Dtest='DomainInvariantValidatorTest,HttpVersionCatalogIT' && echo L3-OK` → `L3-OK` (7 unit + 2 network; real catalog: 3.14.0 → 200, 99.99.99 → 404).
- **2.6** `./mvnw -q -Pdocker-it test -Dtest=DryRunCompileValidatorIT && echo L4-OK` → `L4-OK` (2/2: red + green in the Docker sandbox).
- **2.7** `./mvnw -q test -Dtest='ExecutorBoundaryTest,UpgradeExecutorTest' && echo BOUNDARY-HOLDS` → 6 tests, 0 failures.

## Commits

| Commit | Message |
|---|---|
| f4b8f79 | phase-2.no-verify-guard: mechanical GW-4 guard (reviewer standing condition) |
| 9a61df7 | phase-2.1: Proposal types + strict Jackson boundary |
| 2f356a2 | phase-2.2: Result types, Excerpt reuse, stage hierarchy (pre-State) |
| b430d81 | phase-2.3: Layer 1 PathWhitelistValidator |
| d00141d | phase-2.4: Layer 2 DiffApplyValidator |
| 465130b | phase-2.5: Layer 3 DomainInvariantValidator + VersionCatalog |
| 8490dcc | phase-2.6: Layer 4 DryRunCompileValidator |
| ac0018c | phase-2.7: Executor boundary + signature test |

Tag: `phase-2-complete` (annotated; gate summary in the message).
Hook attestation — every commit above ran the pre-commit hook (`0 violations` each; the final amended 2.7 commit included the hook pass). **Standing condition (Phase-1 incident), closed:** the compound-fallback failure mode is now structurally impossible — the protocol checker has a mechanical GW-4 rule that fails any commit whose `scripts/*.sh` or pre-commit hook contains the literal `--no-verify` (committed as `phase-2.no-verify-guard` with 2 self-tests; `test_check_protocols.py` 10/10). Additionally the grep audit shows the only remaining occurrences are the doc prose that *documents the ban* (docs/protocol.md + phase reports), which the rule deliberately does not scan. The checker itself blocked a real commit twice this phase (see below) — evidence the hook is load-bearing, and I never bypassed it.

## LEARN audit

New LEARN comments this phase: **005** (Proposals.kt:8 — the Kotlin essay), **006** (UpgradeExecutor.kt:16 — the boundary essay), **007** (PathWhitelistValidator.kt:8), **008** (DiffApplyValidator.kt:7). Index updated in the same commits; checker-recomputed locations verified (`python3 scripts/check_protocols.py` → `0 violations`). Note the checker caught two real defects before they could enter history: (a) a `See also: … LEARN[006]` forward-reference was misparsed as a marker (fixed: markers must start a comment line; cross-references are not blocks; self-test added); (b) LEARN[006] lands after [007] per the plan's own placement table, so strict gaplessness was refined to slots 1–16 (pre-allocated, transient gaps by design) with `--full` auditing 1–16 completeness (documented in docs/protocol.md + self-tests).

### Quoted LEARN (one, in full) — LEARN[006], `src/main/kotlin/com/renovator/execution/UpgradeExecutor.kt`

> ```text
> // LEARN[006] The enforcement boundary: validation is code, not prompts
> // Why this way: once proposals cross into execution, nothing may be able to argue
> //   about them. A prompt can say "always validate first" — a type can't be argued
> //   with. So the executor's public methods accept ONLY ValidatedPlan/ValidatedPatch
> //   (types with private constructors, sealed per §7.6), and even a Validated* that
> //   somehow exists must still be refused unless its proof's digest recomputes from
> //   the payload and the proof names the mandatory layers. Validation is a computed
> //   fact, not an annotation someone read.
> // Good sides: the enforcement point is locatable (one class); misuse fails loudly
> //   (UnvalidatedProposalException, not a silent skip); the ExecutorBoundaryTest
> //   proves the API surface by reflection so it can't drift; the blackboard never
> //   holds an unvalidated action input.
> // Drawbacks: the API is clunkier (wrapping types, factories); JVM reflection can
> //   still build the types in-process (KL-07 — documented, accepted: the boundary
> //   defends the LLM/planner path, not malicious in-process code); and proof fields
> //   must be serialized deterministically — hence ProposalJson, one shared mapper.
> // Concept: think "sealed envelope with a wax seal": the seal (digest + mandatory
> //   layers) is checked twice — once when the envelope is closed by the validation
> //   package, once when the executor opens it. The type system prevents CARELESS
> //   code from making its own envelope; the digest prevents clever code from forging
> //   one. Enforcement lives at the boundary because prompts are suggestions and
> //   types are facts.
> // See also: PLAN §4.2, §7.6, §7.7; validation/Validated.kt; LEARN[007]-like layer reasoning
> ```

**Restate-test self-assessment:** teaches what the code cannot show — *why* the boundary exists (prompts can be argued with, types can't), *what the double-check is for* (type seal prevents careless code, digest prevents clever code), the *accepted trade-off* (KL-07 reflection caveat is a scoping decision, not an oversight), and the *determinism requirement* (one shared canonical mapper — otherwise digest comparison is meaningless). All four ≥6-line fields are justified; a reader of the code alone would see the checks, not the reasoning and the rejected alternatives.

## Deviations & limitations (per §13.3, cause + evidence; no silent drift)

1. **Jackson generation split resolved to Jackson 3** — Boot 4.1's primary stack is `tools.jackson.*` (3.1.5); its own pom documents "3.x retains dep to annotations 2.x", so proposal annotations remain `com.fasterxml.jackson.annotation.*` while machinery is `tools.jackson.*`; YAML via `YAMLMapper.builder().addModule(KotlinModule...)`. Phase-1's Jackson-2 explicit deps were switched to Jackson 3 (evidence: `dependency:tree`, the annotation-scan probe that found no `tools.jackson.*` annotation classes, and the mapper builder API).
2. **`CompileCheckResult.skipped` field added** (refinement, §5 defines success+errors only) — distinguishes "dry-run-compile=off (not run)" from "ran and passed", so a caller can never mistake skipped for green. Noted in code + this report.
3. **`validated*` "sealed" realized as private-ctor + final + same-file factory** — Kotlin semantics: sealed classes cannot be instantiated even by their companion (compile error "Sealed types cannot be instantiated" — evidence in the two failed builds); the plan's intent (only the validation package constructs; bypass doesn't compile) is met and proven.
4. **`domain/Results.kt` split** (Phase-1 subset + Phase-2 additions; `Validated*` live in `validation/Validated.kt` per Task 2.7's placement — §5's Results.kt listing is superseded by the task-level file map).
5. **Stub removal in Task 2.1** — the Task-0.3 shell agent + `AgentShellWiringTest` were removed with the stub types (whose semantics were stub-shaped by design, per Task 0.3's own wording). Task 3.1 rebuilds the agent on the real domain with richer tests.
6. **java-diff-utils 4.17 position semantics** — empirically probed: for *parsed* diffs, `delta.source.position` is the 0-based index of the hunk's first line (the rendered `@@ -a,b` header is a+1); library-generated deltas (no context) differ. Documented in `DiffApplyValidator` where it matters. Also: artifact coordinates `io.github.java-diff-utils:java-diff-utils`, package `com.github.difflib`.
7. **L4 IT signal names** — the plan's "naming StringTools.java and escapeSql" adapted to the drift already absorbed in Phase 1 (file `EscapeSqlFormatter.java`, symbol `StringEscapeUtils`); phase-1 report cross-reference.
8. **Checker refinements documented** (See-also-as-reference, pre-allocated slots 1–16) — in docs/protocol.md.

## KL entries

- **KL-07 opened** (pre-declared: yes / marker `TODO(review) KL-07` at `UpgradeExecutor`, row added): reflection caveat — documented honestly, tested by the signature test (garbage-proof refusal).
- **KL-10 opened** (pre-declared: yes / marker `TODO(review) KL-10` at `DiffApplyValidator`, row added): binary/rename/deletion diffs rejected by scope.
- **KL-12** marker/ledger intact (1:1 verified by the checker; implementation scheduled at the Phase-3 LLM-call wrapper).
