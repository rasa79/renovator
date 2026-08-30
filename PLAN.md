# PLAN.md — Renovator

**Resilience at the code layer: an agent that proposes, a pipeline that disposes, an executor that only accepts the validated.**

- **Project:** Renovator — Kotlin + Spring Boot 4 + Embabel agent with dynamic GOAP planning
- **Executor:** DeepSeek Harness (dsh), Standard mode (see §13)
- **Authoring date:** 2026-08-30. All versions and Embabel capabilities below were verified against live sources on this date (§2, §3).
- **Audience for all LEARN comments and docs:** a competent Java/Spring engineer new to Kotlin and to agentic planning.
- **Trilogy framing (binding for all prose this plan produces):** "We know exactly which property makes agentic automation safe — a deterministic judge and cheap reversibility — and we can name the problem classes where it isn't." Every claim in README, docs, and phase reports must be consistent with this bounded framing. No marketing language.

**How to use this plan.** This file is the single source of truth. Task 0.0 (Phase 0) materializes it as `PLAN.md` at the repo root; from then on it is maintained in-repo. Execute phases strictly in order (§14); each phase ends at a hard gate (§14, per-phase "Phase gate" block). The working protocol in §10 is load-bearing, not advisory. If a fact in this plan conflicts with live documentation at execution time, the version-verification rule (§13.3) governs — absorb drift explicitly, never silently.

---

## 1. Fixed decision table (D1–D15 — do not reopen, soften, or alter)

| # | Decision | Value | Rationale |
|---|----------|-------|-----------|
| D1 | Language | **Kotlin**, JVM 25 toolchain | Embabel is Kotlin-native; data classes + null-safety + sealed classes sharpen the guardrails story — a rejected proposal is a *type error*, not a code review comment |
| D2 | Framework | Spring Boot **4.x** + Embabel agent **latest stable** (pinned: Spring Boot 4.1.1, Embabel 1.5.1 — verified 2026-08-30, §3) | Dynamic GOAP planning is the subject of the project |
| D3 | Build system of *this* repo | Maven; `./mvnw verify` is the gate command | Owner fluency; consistency with the rate-limiter workflow |
| D4 | Fixture-repo build system | **Maven only** | One build tool to parse and execute; scope control |
| D5 | LLM providers | Dual: cloud (OpenAI-compatible) default + local Ollama optional; one client abstraction; `LLM_PROVIDER` env switch; **zero code change** between modes | Same discipline as Sentinel; weak-model resilience is part of the story. Embabel's `ModelProvider`/`LlmService` abstraction is the single client layer (verified §2, C-8) |
| D6 | Structured output | Embabel typed binding (`createObject` / `creating(...)`) into Kotlin data classes is the **only** LLM-output path; malformed output and hallucinated fields are rejected at deserialization, never massaged | Layer-0 guardrail comes from the framework, not prompt discipline (verified §2, C-1) |
| D7 | Build execution | Sandboxed: every candidate build runs in a throwaway Docker container over a pristine workspace copy — **never in-place** on the target repo | Reversibility is the property that makes agent error recovery safe |
| D8 | Validation | Five-layer pipeline (§7), each layer a pure function `Proposal → ValidationResult`, ordered cheap → expensive | Defense in depth; failures are typed and reason-carrying so the planner can replan around them |
| D9 | Cost asymmetry | Embabel action costs encode validator expense: cheap checks gate every proposal; full compile gates only commit-candidacy | The planner prefers plans that fail cheap — an interview-grade design point (verified §2, C-4) |
| D10 | Persistence | Agent process persistence via the verified `AgentProcessRepository` SPI (§2, C-5) with our own durable implementation; demo includes kill-and-resume mid-upgrade | Direct JVM answer to Sentinel's Postgres checkpointer |
| D11 | HITL | Embabel `WaitFor` form submission for approval gates (approve plan, approve commit candidate, resolve blocker); programmatic-submission fallback pre-declared (§2, C-6) | First-class in Embabel; mirrors Sentinel's interrupt/Command |
| D12 | API | REST + SSE progress stream (Spring MVC `SseEmitter`); replay-then-tail semantics like Sentinel's stream | The reviewer watches the planner think |
| D13 | Evals | Mock mode (canned LLM responses, deterministic, **100% threshold**) + live mode (real LLM, floor: `fixture-clean` and `fixture-no-path` must pass = ≥ 50%, all four reported); fixture repos with *known* upgrade outcomes double as the eval dataset | Sentinel's eval discipline carried over |
| D14 | Observability | Micrometer + Prometheus for the service; full blackboard trajectory persisted per run (every proposal, validation, plan event) | "Show me every decision the agent made" — the audit trail is a feature |
| D15 | Dev/build topology | Repo on the **WSL2-native filesystem** (`~/dev2/Renovator`, never `/mnt/c`); dsh executes **natively in WSL2** (not containerized); IntelliJ IDEA on Windows in WSL-remote mode is review-only; Docker Desktop with WSL2 integration provides sandbox build containers; cloud LLM default; Ollama is an optional Windows-side service started on demand with `OLLAMA_HOST=0.0.0.0` (default `127.0.0.1` binding is invisible to WSL2), reached via `LLM_PROVIDER=ollama` + `LLM_BASE_URL` with zero code change | Owner's laptop constraints; same discipline as Sentinel's D4/D17 |

---

## 2. Verified Embabel capability matrix

Every Embabel capability this plan relies on was verified on **2026-08-30** against the official documentation and source on the `main` branch of `github.com/embabel/embabel-agent` (1.5.2-SNAPSHOT; latest release 1.5.1) and Maven Central. Context7 library `/embabel/embabel-agent` was used to locate the doc set. **Rule (binding):** at execution time, before writing any Embabel API call, re-query the current docs (Context7 first, then the GitHub paths cited below). If a cited API has drifted, apply §13.3.

| ID | Capability | Status | Verified at (citation) | Used in | Drift fallback (pre-declared) |
|----|-----------|--------|------------------------|---------|-------------------------------|
| C-1 | Typed LLM binding into data classes: `context.ai().withLlm(...).createObject(prompt, T::class.java)`, `creating(T::class.java).fromTemplate/fromPrompt`, `createObjectIfPossible`; native structured output mode sends a strict JSON Schema (`additionalProperties: false`) to OpenAI-compatible providers; Embabel owns schema generation and object binding | **VERIFIED** | `embabel-agent-docs/.../reference/llms/page.adoc` (sha `0c79699`, section "Native Structured Output"); `reference/flow/page.adoc` (sha `6dad36d`, §Binding) | D6; Phase 3 LLM actions; Layer 0 (§7) | If native strict mode unavailable for the configured provider, force Jackson `FAIL_ON_UNKNOWN_PROPERTIES` on the binding path and prove rejection via `LLMBindingStrictnessTest` (Task 3.2) — either way Layer 0 holds |
| C-2 | `@State` loops: `@State` annotation, inherited through hierarchy; parent sealed-interface pattern; `clearBlackboard = true` on looping actions; `canRerun = true`; state scoping hides previous state objects; Kotlin state classes must be top-level | **VERIFIED** | `reference/states/page.adoc` (sha `e3b8970`) incl. full Kotlin `WriteAndReviewAgent` example with looping states | Phase 4 `UpgradeStage` hierarchy (Analyzing/Planning/Applying/Verifying/Repairing/Blocked/Done) | None needed — core, documented, tested-in-docs feature. If semantics drift: model stages as plain blackboard objects with explicit precondition gating (KL entry required) |
| C-3 | `WaitFor` human-in-the-loop: `WaitFor.formSubmission(prompt, T::class.java)` parks the process in `WAITING`; submitted form is bound to the blackboard | **VERIFIED** (API and lifecycle) | `reference/states/page.adoc` §"Human-in-the-loop with WaitFor"; source package `com.embabel.agent.core.hitl` exists in `embabel-agent-api` 1.5.1 | D11; Phase 5 approval gates; Phase 4 escalation (`requestHumanDecision`) | See C-6 |
| C-4 | Action costs & preconditions in GOAP: `@Action(cost = 0.0..1.0, value = …)`, `pre = ["spel:…"]` SpEL preconditions, `@Condition` methods, A* cost-based plan selection, replanning after every action (OODA loop) | **VERIFIED** | `reference/planners/page.adoc` (sha `99dcaa5`, §"Action Cost and Value", SpEL `pre` examples); `reference/flow/page.adoc` §Planning; source `core/Condition.kt`, `core/Goal.kt` | D9; Phase 3 task 3.3 (cost table); guardrail graph preconditions throughout | If SpEL `pre` syntax drifts, fall back to `@Condition`-named conditions only (verified same page); costs are advisory to the planner either way |
| C-5 | Process persistence SPI: `com.embabel.agent.core.AgentProcessRepository` interface + `AbstractAgentProcessRepository` (template method enforcing ephemeral rules); `ProcessOptions.ephemeral`; `ProcessOptions.blackboard` allows starting a process from a given blackboard state | **VERIFIED — SPI only.** Embabel 1.5.1 ships **no durable implementation** in-repo (module list checked); default behavior is in-memory | Source `embabel-agent-api/.../core/AgentProcessRepository.kt` (sha `667832d`); `reference/agent-process/page.adoc` §ProcessOptions; `reference/flow/page.adoc` §Context (`ContextRepository` default in-memory) | D10; Phase 4 kill-and-resume | **Design consequence (not optional):** we implement `JsonFileAgentProcessRepository` ourselves (Task 4.5). If round-tripping a live `AgentProcess` fails at execution time, persist *our own* `RunSnapshot` (typed blackboard domain objects + stage + attempts) and resume by seeding a fresh process via `ProcessOptions.blackboard` — resume semantics preserved; record as KL-08 |
| C-6 | Programmatic `WaitFor` resolution from our own REST layer (not the Embabel shell) | **PARTIALLY VERIFIED** — `WaitFor` itself verified (C-3); the exact public API for *submitting* a form programmatically was not confirmed in the docs pages read | `reference/states/page.adoc` (shell-oriented examples) | D11; Phase 5 | **Pre-declared fallback:** if no programmatic submission path exists in 1.5.1, approval gates become a deterministic blackboard action: REST layer places a typed `HumanDecision` on the blackboard; the gate action (`canRerun = true`, precondition = `HumanDecision` present) unparks the flow. Recorded as KL-09 with `TODO(review)` |
| C-7 | Bounded termination: `EarlyTerminationPolicy.maxActions(n)`, `maxTokens(n)`, `hardBudgetLimit($)`, `firstOf(...)`, `ON_STUCK`; wired via `ProcessOptions.control`; process states include `STUCK`, `TERMINATED`, `WAITING`, `KILLED`; `ReplanRequestedException`; `TerminateAgentException` | **VERIFIED** | Source `core/EarlyTerminationPolicy.kt` (sha `b3f02a2`); `reference/termination/page.adoc`; `reference/agent-process/page.adoc` | Planner non-termination bound (Risk R-4); Phase 4 tasks 4.4–4.5 | None needed — confirmed in shipped source |
| C-8 | Dual LLM providers with zero code change: `embabel-agent-starter-openai` (`OPENAI_API_KEY`, `OPENAI_BASE_URL`); `embabel-agent-starter-openai-custom` (any OpenAI-compatible endpoint — explicitly documented for Ollama's OpenAI-compatible API); `embabel-agent-starter-ollama` (native, `spring.ai.ollama.base-url`); role mapping `embabel.models.llms.<role>` + `default-llm`; small-model resilience knobs `embabel.agent.platform.toolloop.*` | **VERIFIED** | `getting-started/installing/page.adoc` (starter coordinates + env vars + Ollama sections); `reference/llms/page.adoc` §"Tuning for Smaller and Local Models" | D5; Phase 0 tasks 0.4–0.5; live evals | Cloud default = `embabel-agent-starter-openai`; Ollama mode = same starter family via `openai-custom` base-url override (preferred: one client code path) — the `ollama` native starter is the documented alternative |
| C-9 | Mock LLM for deterministic evals/tests: `FakePromptRunner`, `FakeOperationContext` (`expectResponse(...)`), `EmbabelMockitoIntegrationTest` (`whenCreateObject(...)`), `AgentInvocation`, `IntegrationTestUtils.dummyAgentPlatform()`, `AgentMetadataReader` | **VERIFIED** | `reference/testing/page.adoc` (full Kotlin examples) | D13; Phases 3–6 tests; eval mock mode | None needed — dedicated test-support artifact `embabel-agent-test-support:1.5.1` confirmed on Central |
| C-10 | Observability starter: `embabel-agent-starter-observability` | **VERIFIED exists** on Central (1.5.1) | repo1.maven.org metadata, 2026-08-30 | D14 (optional adoption; our own Micrometer meters are the primary path) | If it pulls unwanted machinery, skip it — our meters stand alone |

**Explicitly not assumed:** any Embabel GUI/console, Gradle support, durable process storage out of the box, programmatic HITL submission (see C-6), streaming structured output.

---

## 3. Version pins (verified 2026-08-30 against repo1.maven.org / Docker Hub / GitHub)

| Component | Pin | Source checked |
|---|---|---|
| Java (toolchain) | 25 (Eclipse Temurin) | owner's JDK 25.0.2 on PATH; sandbox image below |
| Spring Boot (parent) | **4.1.1** (latest GA; 4.2.0-M1 milestone exists — do not use) | repo1 `org/springframework/boot/spring-boot-starter-parent` |
| Embabel | **1.5.1** (`embabel-agent-starter`, `embabel-agent-starter-openai`, `embabel-agent-starter-openai-custom`, `embabel-agent-test-support`, `embabel-agent-starter-observability`) | repo1 `com/embabel/agent/*` metadata; repo `main` is 1.5.2-SNAPSHOT |
| Spring AI | 2.0.1 (transitive via Embabel — do not pin independently) | Embabel root pom comment (spring-ai-openai 2.0.1) |
| Kotlin | Boot-managed `kotlin.version` first; if Embabel metadata requires newer, set **2.3.21** explicitly | repo1 `org/jetbrains/kotlin/kotlin-stdlib` (GA line: 2.3.21, 2.4.10) |
| Maven (wrapper) | 3.9.x (owner has 3.9.11 via sdkman) | — |
| jqwik (property tests) | **1.10.1** | repo1 `net/jqwik/jqwik` |
| java-diff-utils (Layer 2) | **4.17** (`io.github.java-diff-utils:java-diff-utils`) | repo1 |
| Maven Model API + ComparableVersion (Layer 3) | `org.apache.maven:maven-model` **3.9.16**, `org.apache.maven:maven-artifact` **3.9.16** (GA line; 4.0.0-rc-6 exists — do not use) | repo1 |
| maven-enforcer-plugin (fixtures) | **3.6.3** | repo1 |
| ktlint Maven plugin (lint decision: **ktlint**, not detekt) | `com.github.gantsign.maven:ktlint-maven-plugin` **3.7.1** | repo1 |
| Sandbox Docker image | `maven:3.9.11-eclipse-temurin-25` (verify exact tag at execution: `curl -s "https://hub.docker.com/v2/repositories/library/maven/tags/?name=eclipse-temurin-25"`) | Docker Hub API (30 `eclipse-temurin-25` tags exist) |
| fixture-clean deps | `org.apache.commons:commons-lang3` **3.12.0 → 3.14.0** (3.20.0 is latest; mid pins chosen for stability) | repo1 |
| fixture-api-removal deps | `commons-lang:commons-lang` **2.6** (has `org.apache.commons.lang.StringEscapeUtils.escapeSql`) → `commons-lang3` **3.14.0** (no `escapeSql`; package renamed) | repo1 + stable library history |
| fixture-transitive-conflict deps | direct `com.google.guava:guava` **32.1.2-jre → 33.4.8-jre** + fixed `com.google.inject:guice` **7.0.0** (transitively pins guava) + enforcer `dependencyConvergence` | repo1 (versions exist); convergence behavior verified by command in Task 1.3 with two named alternate version sets |
| fixture-no-path target | `commons-lang3` **99.99.99** (must 404 forever) | repo1 HEAD check is the test |
| Jackson | Boot-managed | — |
| JUnit 5 / AssertJ / Mockito | Boot-managed (`spring-boot-starter-test`) | — |

**Re-verification rule (binding, from §8 of the brief):** every pin above is re-checked at execution time against Maven Central / official docs *before first use in a phase gate*. If unavailable or incompatible: bump minimally, re-run the phase's verify command, record an **environment note** in the phase report. Never redesign silently around drift.

---

## 4. Architecture

### 4.1 The loop (the product)

```
            ┌─────────────────────────────────────────────────────────┐
            │                     Embabel GOAP planner                │
            │        (replans after every action — OODA loop)         │
            └─────────────────────────────────────────────────────────┘
                 │                                    ▲
                 ▼ typed proposals (data classes)      │ typed failures on blackboard
        ┌─────────────────┐   ValidationRejection ─────┤
        │  LLM actions     │──────────────┐             │
        │ (createObject    │              ▼             │
        │  only, D6)       │   ┌────────────────────┐   │
        └─────────────────┘   │ VALIDATION PIPELINE │   │
                              │ L0 schema binding   │   │
                              │ L1 path whitelist   │   │
                              │ L2 diff applies     │   │
                              │ L3 domain invariants│   │
                              │ L4 dry-run compile  │   │
                              └────────────────────┘   │
                                       │ Validated* only
                                       ▼                │
                              ┌────────────────────┐   │
                              │ DETERMINISTIC       │   │
                              │ EXECUTOR            │   │
                              │ (type-sealed input) │   │
                              └────────────────────┘   │
                                       │ WorkspaceSnapshot
                                       ▼                │
                              ┌────────────────────┐   │
                              │ SANDBOXED BUILD     │   │ BuildResult / TestResult
                              │ (Docker, throwaway) │───┘
                              └────────────────────┘
```

### 4.2 The enforcement-boundary invariant (state verbatim in code and docs)

> **The executor's public methods accept only `Validated*` types. Raw LLM proposals (`CodePatch`, `UpgradePlan`, `VersionChange`) are type-incompatible with the execution boundary: they cannot be passed without a cast the codebase never performs. `Validated*` instances are created only inside the validation package (internal constructors) and carry a `ValidationProof` binding the payload's SHA-256 digest to the checks that passed; the executor recomputes the digest and rejects forged, truncated, or mismatched proofs. Enforcement lives at the boundary, not in prompts.**

### 4.3 Module/package map (single Maven module, root package `com.renovator`)

```
renovator/
├── PLAN.md                      # this file (Task 0.0)
├── pom.xml
├── mvnw, mvnw.cmd, .mvn/
├── README.md                    # Phase 7 (Appendix A structure is mandatory)
├── LEARN_INDEX.md               # protocol (§10.1)
├── KNOWN_LIMITATIONS.md         # protocol (§10.2)
├── fixtures/                    # Phase 1 — plain Maven projects, NOT modules of this build
│   ├── fixture-clean/
│   ├── fixture-api-removal/
│   ├── fixture-transitive-conflict/
│   └── fixture-no-path/
├── scripts/
│   ├── check_protocols.py       # load-bearing lint (§10.4, Appendix C)
│   ├── install_hooks.sh         # installs pre-commit hook
│   ├── verify-ktlint-gate.sh
│   ├── demo-replan.sh           # Phase 4 demo backbone
│   ├── demo-kill-resume.sh      # Phase 4 persistence demo
│   └── renovator                # CLI (Phase 5)
├── docs/
│   ├── verification-log.md      # Task 0.3 — capability verification record
│   ├── protocol.md              # §10 adapted into repo docs
│   ├── demo-replan.md           # generated from a real run (Phase 4 gate)
│   ├── audit-trail.md           # Phase 6
│   └── phase-reports/phase-{0..7}.md
├── eval/
│   ├── canned/                  # canned LLM responses per fixture (mock eval mode)
│   └── reports/                 # eval outputs
├── var/runs/                    # runtime data (gitignored except .gitkeep): trajectories, process snapshots
└── src/
    ├── main/kotlin/com/renovator/
    │   ├── RenovatorApplication.kt
    │   ├── config/              # RenovatorProperties, LlmProviderConfig
    │   ├── domain/              # proposals, results, stages (Phase 2)
    │   ├── validation/          # layers 1–4, Validated* wrappers (Phase 2)
    │   ├── execution/           # WorkspaceCopier, DockerSandboxRunner, UpgradeExecutor (Phases 1–2)
    │   ├── agent/               # RenovatorAgent, actions/, conditions/, states/ (Phases 3–4)
    │   ├── persistence/         # JsonFileAgentProcessRepository (Phase 4)
    │   ├── audit/               # TrajectoryStore, TrajectoryEvent, RunRegistry (Phase 3)
    │   ├── api/                 # RunController, SseController, DecisionController, dto/ (Phase 5)
    │   └── eval/                # EvalRunner, ExpectedOutcome (Phases 1, 6)
    ├── main/resources/
    │   ├── application.yml
    │   └── prompts/             # the ONLY location of LLM prompts, versioned (*.st)
    └── test/kotlin/com/renovator/…   # mirrors main; IT classes tagged (§10.5, QS-6)
```

---

## 5. Domain model (Phase 2; all Kotlin data classes / sealed hierarchies)

File: `src/main/kotlin/com/renovator/domain/Proposals.kt` — LLM-produced proposals:
- `UpgradeGoal(targets: List<DependencyTarget>, constraints: List<Constraint>)`
- `DependencyTarget(groupId: String, artifactId: String, fromVersion: String, toVersion: String)`
- `Constraint` — sealed: `NoSnapshots`, `MaxHops(n: Int)`, `MustKeepArtifact(groupId, artifactId)`
- `VersionChange(groupId, artifactId, fromVersion, toVersion, scope: ChangeScope)` where `ChangeScope` = `DIRECT | MANAGEMENT` (a `MANAGEMENT` change edits `dependencyManagement` — this is what makes the two-hop/pin replan expressible)
- `CodePatch(filePath: String, unifiedDiff: String, justification: String)`
- `BuildDiagnosis(failedGoals: List<String>, rootCauses: List<RootCause>, suggestedActions: List<ActionHint>)`, `RootCause(symbolOrArtifact, explanation)`, `ActionHint(kind: HintKind, detail: String)` with `HintKind = PIN_TRANSITIVE | MULTI_HOP | PATCH_CODE | ESCALATE`
- `UpgradePlan(steps: List<PlanStep>, rationale: String)`; `PlanStep` — sealed: `VersionStep(change: VersionChange) | PatchStep(patch: CodePatch)` — multi-hop staging (3.2 → 3.3 → 3.4) is a plan with two `VersionStep`s

File: `src/main/kotlin/com/renovator/domain/Results.kt` — deterministic outputs:
- `ValidatedPlan(plan: UpgradePlan, proof: ValidationProof)`, `ValidatedPatch(patch: CodePatch, proof: ValidationProof)` — **the only types the executor accepts**
- `ValidationProof(checkNames: List<String>, contentDigestSha256: String, validatedAt: Instant)` — internal constructor (see §7.6)
- `ValidationRejection(checkName: String, reason: String, offendingContent: String)` — goes on the blackboard to inform replanning
- `CompileCheckResult(success: Boolean, errors: List<CompileError>)`; `CompileError(filePath, line, column, message)`
- `BuildResult(success: Boolean, failedGoals: List<String>, log: Excerpt, durationMs: Long)`
- `TestResult(passed: Int, failed: Int, failures: List<TestFailure>)`
- `Excerpt(head: String, tail: String, truncatedBytes: Long)` — log capture budgeted for LLM context (max 4 KiB head + 8 KiB tail)
- `UpgradeBlocker(summary: String, attempts: List<AttemptRecord>, humanQuestion: String)` — the escalation payload
- `AttemptRecord(planRationale: String, rejectedAt: String?, buildFailedGoals: List<String>, validationRejections: List<ValidationRejection>)`
- `UpgradeComplete(appliedSteps: List<PlanStep>, finalBuild: BuildResult, durationMs: Long)` — goal-achieving terminal object
- `HumanDecision(approved: Boolean, comment: String)` — HITL payload

File: `src/main/kotlin/com/renovator/domain/Stages.kt` — `@State` sealed hierarchy (wired in Phase 4 per C-2):
- `@State sealed interface UpgradeStage` with top-level data classes `Analyzing`, `Planning`, `Applying`, `Verifying`, `Repairing`, `Blocked`, `Done`. Loop `Applying → Verifying → Repairing → Applying` uses `@Action(clearBlackboard = true)`; all loop-carried data travels in the state instances (per the verified states doc: "pass all necessary data through state record fields").

Also `src/main/kotlin/com/renovator/domain/RepoModel.kt`: `RepoModel(dependencies: List<ResolvedDependency>, enforcerRules: List<String>, javaRelease: String)`, produced by the deterministic `analyzeRepository` action.

---

## 6. Action palette

The **planner is dynamic; the palette and the guards are explicit.** Every action is wired by hand with declared preconditions (type signatures + SpEL/`@Condition`), declared costs (D9), and declared outputs. Table = the contract; Task 3.1's `AgentPaletteCompletenessTest` enforces it by reflection over agent metadata.

| Action | Kind | Preconditions (key) | Inputs → Outputs | Cost |
|---|---|---|---|---|
| `analyzeRepository` | deterministic | `UpgradeGoal` on blackboard | `UpgradeGoal` → `RepoModel` | 0.05 |
| `proposeUpgradePlan` | LLM (C-1) | `RepoModel` present; no `ValidatedPlan` yet | `RepoModel` → `UpgradePlan` | 0.30 |
| `validatePlan` | deterministic | `UpgradePlan` present (L1–L3 over every step) | `UpgradePlan` → `ValidatedPlan` **or** `ValidationRejection` | 0.05 |
| `applyValidatedChanges` | deterministic | **`ValidatedPlan` (nothing else type-checks)** | `ValidatedPlan` → `WorkspaceSnapshot` | 0.10 |
| `runBuild` | deterministic (sandbox, D7) | `WorkspaceSnapshot` present | `WorkspaceSnapshot` → `BuildResult` + `TestResult` | 0.60 |
| `diagnoseFailure` | LLM (C-1) | `BuildResult(success=false)` | `BuildResult` → `BuildDiagnosis` | 0.30 |
| `proposePatch` | LLM (C-1) | `BuildDiagnosis` present | `BuildDiagnosis` → `CodePatch` | 0.30 |
| `validatePatch` | deterministic | `CodePatch` present (L1–L2) | `CodePatch` → `ValidatedPatch` **or** `ValidationRejection` | 0.05 |
| `dryRunCompile` | deterministic (sandbox) | `ValidatedPatch`/`ValidatedPlan` present **and** `@Condition commitCandidacyArmed` (tests-not-yet-green) — mandatory before `finalizeUpgrade` | → `CompileCheckResult` | 0.80 |
| `requestHumanDecision` | deterministic + `WaitFor` (C-3/C-6) | plan space exhausted (`UpgradeBlocker` present) **or** approval gate armed | `UpgradeBlocker`/gate → `HumanDecision` | 0.00 |
| `finalizeUpgrade` | deterministic, `@AchievesGoal` | `TestResult(failed=0)` **and** `CompileCheckResult(success)` **and** `HumanDecision(approved)` when gate armed | → `UpgradeComplete` (achieves goal `BuildGreen`) | 0.05 |

Cost rationale (D9): cheap validators (0.05) gate every proposal; LLM calls (0.30) are mid; sandbox operations (0.60/0.80) are expensive and the planner's A* will only route through them when cheaper paths are exhausted — plans that fail cheap are preferred. Costs are planner guidance; correctness never depends on them.

### 6.1 Worked replanning trace (Phase-4 demo backbone, `fixture-transitive-conflict`)

Goal: `guava 32.1.2-jre → 33.4.8-jre`; repo also depends on `guice:7.0.0` (transitively pins guava); enforcer `dependencyConvergence` active.

1. `analyzeRepository` → `RepoModel(guava 32.1.2-jre direct; guice 7.0.0; rule=dependencyConvergence)`
2. `proposeUpgradePlan` → `UpgradePlan([VersionStep(guava→33.4.8-jre, DIRECT)], "single bump")`
3. `validatePlan` → `ValidatedPlan` (L1–L3 pass: version exists, increases, no snapshot)
4. `applyValidatedChanges` → `WorkspaceSnapshot`
5. `runBuild` → `BuildResult(success=false, failedGoals=["maven-enforcer-plugin:3.6.3:enforce"], log=Excerpt(…"dependencyConvergence … guava … 32.1.2-jre via guice … vs 33.4.8-jre direct"…))`
6. State: `Verifying → Repairing` (`@State` transition, C-2)
7. `diagnoseFailure` → `BuildDiagnosis(rootCauses=[RootCause("com.google.guava:guava", "convergence conflict: guice path pins 32.1.2-jre")], suggestedActions=[PIN_TRANSITIVE, MULTI_HOP])`
8. Replan: `proposeUpgradePlan` → `UpgradePlan([VersionStep(guava→33.4.8-jre, MANAGEMENT), VersionStep(guava→33.4.8-jre, DIRECT)], "pin transitive via dependencyManagement, then bump direct")` — **two-hop**
9. `validatePlan` → `ValidatedPlan`; `dryRunCompile` (commit-candidacy armed) → `CompileCheckResult(success=true)`
10. `applyValidatedChanges` → `runBuild` → `BuildResult(success=true)`, `TestResult(failed=0)`
11. `finalizeUpgrade` → `UpgradeComplete` — goal `BuildGreen` achieved

Gate artifact: `scripts/demo-replan.sh` runs this end-to-end and `docs/demo-replan.md` quotes the real trajectory lines.

---

## 7. Validation pipeline spec (D8)

Each layer is a **pure function** `Proposal → ValidationResult` (either a `Validated*` or a `ValidationRejection`), ordered cheap → expensive. Every layer is a named task in Phase 2 with adversarial unit tests; layers 1–2 also carry jqwik property tests.

### L0 — Schema binding (framework-provided, still tested)
Embabel typed binding (C-1) into the data classes of §5. Malformed output never becomes a blackboard object. Tested twice: `LLMBindingStrictnessTest` (Task 3.2 — garbage from the LLM path becomes a typed `ValidationRejection`, not a crash, not an object) and `ProposalTypesTest.rejects unknown keys on deserialize` (Task 2.1 — our own Jackson boundary is strict).

### L1 — Path whitelist (`PathWhitelistValidator`)
Normalize-then-match: resolve separators and `.`/`..` segments *before* glob matching (kills `../` escapes); forbidden patterns (`.git/**`, `**/*.sh`, `**/secrets/**`, `**/.env*`) **beat** allowed patterns (`pom.xml`, `src/main/java/**`, `src/main/kotlin/**`, `src/test/**`). Absolute paths rejected.

### L2 — Diff applies cleanly (`DiffApplyValidator`)
Parse with **java-diff-utils 4.17 — never regex**; apply in-memory to the current file content; any context mismatch → rejection naming the offending hunk index and the expected context line. Binary diffs, rename-only diffs, and deletion diffs are rejected (pre-declared scope, KL-10).

### L3 — Domain invariants (`DomainInvariantValidator` + `VersionCatalog`)
- Version existence: HTTP HEAD on `https://repo1.maven.org/maven2/<g>/<a>/<v>/<a>-<v>.pom` must be 200 (behind the `VersionCatalog` interface; `HttpVersionCatalog` for real runs, `FakeVersionCatalog` for hermetic tests).
- Monotonic increase via `org.apache.maven.artifact.versioning.ComparableVersion` (maven-artifact 3.9.16).
- No `-SNAPSHOT` unless `Constraint`/config explicitly allows.
- Post-edit pom re-parsed via Maven Model API (maven-model 3.9.16): valid model, `<modelVersion>` intact, `<repositories>` entries must be within the allowlist (default: Maven Central only) — supply-chain guard.

### L4 — Dry-run compile (`DryRunCompileValidator`)
All pending validated changes applied to a pristine `WorkspaceCopier` copy; `mvn -q compile` in the Docker sandbox; output `CompileCheckResult`. Toggleable via `renovator.validation.dry-run-compile` (`always | on-commit-candidate | off`, default `on-commit-candidate`) per D9; **mandatory** before commit-candidacy (`finalizeUpgrade` precondition).

### 7.6 The `Validated*` seal
`ValidatedPlan`/`ValidatedPatch`/`ValidationProof` have `internal` constructors; only the validation package creates them. The proof binds `sha256(payload)` to the exact `checkNames` that passed. `UpgradeExecutor` recomputes the digest, requires the mandatory check set, and throws `UnvalidatedProposalException` otherwise.

### 7.7 Signature test (mandatory, quoted verbatim in the phase-2 gate)

**`ExecutorBoundaryTest` — feed the executor garbage directly, bypassing the planner entirely, and prove it refuses:**
- `rejects raw CodePatch JSON POSTed as ValidatedPatch` — deserialization of a forged `ValidatedPatch` fails (no public constructor; unknown/missing proof fields)
- `rejects forged proof whose digest does not match the payload`
- `rejects proof whose checkNames omit mandatory layers` (e.g. proof claiming only L1 ran)
- `rejects a ValidatedPatch constructed by reflection with a garbage proof` (documents the JVM-reflection caveat honestly — KL-07)
- `every public method of UpgradeExecutor declares only Validated-star parameter types` (reflection over the API surface — a raw `CodePatch` parameter anywhere fails the build)

Enforcement at the boundary, not the prompt — this test is the project's thesis in executable form.

---

## 8. Failure-injection harness & fixture repos (the deterministic judge)

Same philosophy as Sentinel's chaos endpoints and the rate limiter's Toxiproxy suite: **deterministic breakage the owner controls.** Four small Maven projects committed under `fixtures/` (plain directories with their own `pom.xml`; **not** modules of the Renovator build; never built in place by the runtime — only via `WorkspaceCopier` + sandbox, D7).

Each fixture carries `expected-outcome.yml`; the set of four files **is** the eval dataset (D13). Schema (validated by `OutcomeYamlSchemaTest`, Task 1.5):

```yaml
fixture: fixture-clean                      # directory name
goal:
  targets: [{ groupId: org.apache.commons, artifactId: commons-lang3,
              fromVersion: "3.12.0", toVersion: "3.14.0" }]
  constraints: [NoSnapshots]
expectedTerminalState: UpgradeComplete      # UpgradeComplete | UpgradeBlocker
mustVisitStages: [Analyzing, Planning, Applying, Verifying]
mustNotVisitStages: []
maxAttempts: 6                              # ceiling on plan attempts (bounded termination)
requiredArtifacts: [BuildResult]            # types that must appear in the trajectory
notes: happy path — zero breakage
```

### 8.1 `fixture-clean` (happy path)
- `pom.xml` (Java 17 release, JUnit 5), one class `com.example.clean.StringTools` using `org.apache.commons.lang3.StringUtils.reverse`, one test.
- Dependency: `commons-lang3:3.12.0`; goal: → `3.14.0`. Both versions exist; no API break for the used surface.
- **Expected outcome:** `UpgradeComplete`; stages Analyzing→Planning→Applying→Verifying→Done; exactly one sandbox build; no Repairing.

### 8.2 `fixture-api-removal` (compile failure → code patch)
- Code calls `org.apache.commons.lang.StringEscapeUtils.escapeSql(name)`; dependency `commons-lang:commons-lang:2.6`.
- Goal: migrate to `org.apache.commons:commons-lang3:3.14.0` (groupId/artifactId change; `escapeSql` does not exist in lang3 — the compile error *names* the symbol, which is precisely the signal the planner consumes).
- **Expected outcome:** `UpgradeComplete` via one repair loop — `BuildResult(failure)` → `BuildDiagnosis` → `CodePatch` replacing the call with an equivalent local implementation → `ValidatedPatch` → green. Stages must include Repairing. `maxAttempts: 10`.

### 8.3 `fixture-transitive-conflict` (single-hop fails; pin or two-hop resolves)
- Direct deps: `com.google.guava:guava:32.1.2-jre` (upgrade target `33.4.8-jre`) and fixed `com.google.inject:guice:7.0.0`; `maven-enforcer-plugin:3.6.3` with `dependencyConvergence` bound to `validate`. Code uses `ImmutableList.copyOf` only (stable API surface).
- Applying the goal directly produces a **deterministic enforcer failure** naming both guava paths. Correct resolutions (matching the brief): (a) `MANAGEMENT`-scope `VersionChange` pinning guava in `dependencyManagement`, then the direct bump — the two-hop trace of §6.1; (b) a combined plan upgrading both artifacts consistently.
- **Authoring-time verification (part of Task 1.3, mandatory):** baseline `mvn -q validate` green; after applying the direct bump, `mvn -q validate` fails with `dependencyConvergence` and the message names guava; `mvn -q dependency:tree -Dincludes=com.google.guava:guava` output pasted into the fixture's README. **If the guice transitive pin has drifted:** alternate set B = guava `31.1-jre → 33.4.8-jre` with `guice:6.0.0`; alternate set C = any pair (direct `A` with upgrade, fixed `B`) where `dependency:tree` proves `B` transitively pins `A` at ≠ target — the mechanism (enforcer convergence failure + pin/two-hop resolution) is the fixture, the exact coordinates are replaceable under the §13.3 rule with an environment note.
- **Expected outcome:** `UpgradeComplete` via exactly one failed build then a two-hop plan; `mustVisitStages: [Analyzing, Planning, Applying, Verifying, Repairing]`; `maxAttempts: 12`.

### 8.4 `fixture-no-path` (honest termination)
- Clean pom (same shape as fixture-clean); goal: `commons-lang3 → 99.99.99` (Layer-3 existence check 404s — verified 2026-08-30 and stable by construction), constraint `NoSnapshots`.
- **Expected outcome:** `UpgradeBlocker` + human escalation is the *only* correct terminal state. Every plan is rejected at L3; the attempt budget (§6, C-7) caps the loop; the blocker names every attempt and its rejection. `mustNotVisitStages: [Applying]`; `expectedTerminalState: UpgradeBlocker`. This fixture is what proves error recovery terminates honestly instead of flailing.

### 8.5 Sandbox runner spec (Task 1.6; D7)
- One throwaway container per build: `docker run --rm --memory=2g --cpus=2 -v <workspace-copy>:/work:ro -v renovator-m2-cache:/m2 -w /work maven:3.9.11-eclipse-temurin-25 mvn -q -Dmaven.repo.local=/m2/repository <goals>`.
- Hard timeout enforced by the runner (kill container, record `BuildResult(success=false, failedGoals=["<timeout>"])`).
- Output captured and truncated to `Excerpt` (4 KiB head + 8 KiB tail; `truncatedBytes` recorded).
- The source fixture directory is never mounted; hashes of the source tree are asserted unchanged in the runner IT.
- Implementation choice: **Docker CLI via `ProcessBuilder`** — zero extra dependencies, fully inspectable in demo scripts. Testcontainers was considered and rejected: lifecycle magic the project doesn't need, and the judge must stay transparent (recorded in LEARN[004]).

---

## 9. Reading guide

Sections 1–8 specify the design (decisions, capabilities, versions, architecture, model, palette, pipeline, fixtures). Sections 10–14 govern the process (working protocol, risks, scope, executor environment, phase order and gates). Section 15 decomposes Phases 0–7 into executable tasks. Appendices A–C carry the README mandate, the phase-report template, and the protocol-checker spec. When design and process conflict, §1 (D-table) wins, then §10 (protocol), then everything else.

---

## 10. Working protocol (adapted from the Sentinel protocol — binding)

### 10.1 LEARN comments protocol
1. Every `LEARN[NNN]` block uses the **5-field format**:
   ```
   // LEARN[007] Normalize-then-match path whitelist
   // Why this way: …
   // Good sides: …
   // Drawbacks: …
   // Concept: …
   // See also: LEARN[006], docs/protocol.md   (optional)
   ```
2. Global zero-padded numbering (`001`, `002`, …), **never reused**, even if a comment is deleted (the index records deletions as struck-through rows).
3. `LEARN_INDEX.md` — numbered table (number, title, `file:line`, concept tag) updated **in the same commit** as the comment.
4. Cross-reference (`See also:`) instead of duplicating an explanation. A labeled cross-reference *stub* (`LEARN-REF[007] → see LEARN[007]`) is exempt from the length floor and is the only permitted short form.
5. **Restate test:** every comment must teach something the code cannot show — the *why*, the trade-off, the rejected alternative. If a competent reader could reconstruct the comment from the code alone, it fails. **Floor: ~6 lines** (stubs excepted).
6. Audience: a competent Java/Spring engineer **new to Kotlin and agentic planning**. Anchor Kotlin concepts to Java analogies (data class ≈ record, sealed class ≈ sealed interface, null-safety vs `Optional`) and Embabel concepts to workflow-engine analogies (blackboard ≈ process variables, `WaitFor` ≈ BPMN human task).
7. Phase reports quote **one LEARN comment in full** with a restate-test self-assessment (Appendix B).
8. **Mandatory deep-dive placements** (pre-allocated, gapless):
   - LEARN[005] Kotlin-for-a-Java-engineer — `domain/Proposals.kt` header (Task 2.1)
   - LEARN[009] GOAP/dynamic planning vs static graph wiring — the Sentinel contrast; **the canonical essay of the repo** — `agent/RenovatorAgent.kt` (Task 3.2)
   - LEARN[006] validation-pipeline enforcement-boundary principle — `execution/UpgradeExecutor.kt` (Task 2.7)
   - LEARN[010] action-cost asymmetry — `agent/actions/` cost table (Task 3.3)
   - LEARN[012] `@State` loops (`clearBlackboard` semantics) — `agent/states/Stages.kt` (Task 4.1)
   - LEARN[013] Embabel process persistence vs Sentinel's Postgres checkpointer — `persistence/JsonFileAgentProcessRepository.kt` (Task 4.5)
   - Supporting essays: LEARN[001] dual-provider single abstraction (0.4); LEARN[002] load-bearing protocol lint (0.6); LEARN[003] judge-before-judged (1.5, `fixtures/README.md`); LEARN[004] sandbox reversibility + Excerpt budget (1.6); LEARN[007] normalize-then-match (2.3); LEARN[008] real diff library, never regex (2.4); LEARN[011] typed blackboard ≈ process variables (3.1); LEARN[014] honest termination & attempt budget (4.4); LEARN[015] `WaitFor` ≈ BPMN human task + SSE replay-then-tail (5.2/5.3); LEARN[016] the audit trail is a feature (6.4).

### 10.2 Deferred-work protocol (`TODO(review)` ↔ `KNOWN_LIMITATIONS.md`)
- Every `TODO(review)` marker in code ↔ **exactly one** `KNOWN_LIMITATIONS.md` entry, created in the **same commit**. Entry format: `KL-NN | file:line | limitation | user-visible: yes/no | rationale`.
- User-visible entries additionally require one sentence in README's Known Limitations section.
- The checker (§10.4) enforces marker↔entry count **both directions**; an orphan on either side fails the commit.
- Pre-declared seeds (must exist from Task 0.6; extended as phases land): KL-01 single-process, single-run-at-a-time agent; KL-02 no authentication on the control API — sandbox containers are the security boundary, demo posture; KL-03 Maven-only fixture scope (D4), Gradle explicitly out; KL-04 LLM diagnoses are advisory — correctness is asserted only by build/test outcomes, never model confidence; KL-05 eval N is small (4 fixtures) — smoke signal, not a benchmark; KL-06 Ollama path may be slow on modest hardware — timeouts tuned accordingly, live-eval threshold applies to whichever provider is configured. Added by design work in this plan: KL-07 JVM reflection can construct `Validated*` in-process — the boundary defends the LLM/planner path, not malicious in-process code (Task 2.7); KL-08 process-persistence resume may be implemented via `RunSnapshot` re-seeding rather than live `AgentProcess` round-trip (C-5); KL-09 programmatic `WaitFor` submission may be replaced by the blackboard-poll gate (C-6); KL-10 binary/rename/deletion diffs rejected by scope (L2); KL-11 execution is WSL2-native only (D15).

### 10.3 Git clauses (GW-1…GW-4)
- **GW-1** One commit per task. Message: `phase-N.M: <task title>`. No mixed-task commits; no `wip`.
- **GW-2** Annotated tag `phase-N-complete` (`git tag -a`) is created **only after** the phase gate (all commands green, protocol check, LEARN audit) passes. Tag message lists gate evidence.
- **GW-3** `git status --porcelain` is empty at every phase boundary and before every tag.
- **GW-4** The pre-commit hook runs on every commit; `--no-verify` is forbidden. The phase report lists every commit hash + tag + an attestation that the hook ran (paste one hook output line per commit).

### 10.4 Mechanical protocol checker (load-bearing)
`scripts/check_protocols.py` (Python 3, stdlib only — Sentinel parity), installed as `.git/hooks/pre-commit` by `scripts/install_hooks.sh`. Checks (full spec: Appendix C):
1. Every `LEARN[NNN]` has all 5 fields (or is a `LEARN-REF` stub), meets the ~6-line floor.
2. Numbering gapless from 001; no reuse; `LEARN_INDEX.md` rows match code markers exactly (number, file, line, title).
3. `TODO(review)` count == `KNOWN_LIMITATIONS.md` entry count, 1:1 by id, both directions; user-visible entries have a README sentence.
4. Prompts exist only under `src/main/resources/prompts/` (grep for prompt-shaped string literals elsewhere is advisory-warn in phases 0–2, hard-fail from Phase 3 on).
5. `git status --porcelain` empty when invoked with `--phase-boundary`.
The hook failing blocks the commit. That is the point (LEARN[002]).

### 10.5 Execution rules
- No silent scope cuts, no silent design decisions. Any deviation → environment note in the phase report + (if durable) a KNOWN_LIMITATIONS entry.
- Explicit action/palette wiring only: the *planner* is dynamic; the *palette, preconditions, costs, and guards* are hand-declared and reflection-tested (`AgentPaletteCompletenessTest`).
- Prompts live in one versioned location: `src/main/resources/prompts/*.st`. Editing a prompt is a commit of its own, titled `phase-N.M: prompt: <name>`.
- Test taxonomy: unit tests run in `./mvnw verify`; Docker-dependent and live-LLM tests are `*IT` classes behind profiles `docker-it` / `llm-it` (see phase gates). Nothing is ever `@Disabled` without a KL entry.
- Pinned versions verified per §3; drift absorbed per §13.3.

### 10.6 Quality-standard clauses (QS-1…QS-5)
- **QS-1** Every task lands with its named tests **green in the same commit** — no "tests later".
- **QS-2** Every Demonstration line is executed verbatim; the command and observed output are pasted into the phase report (and referenced as dsh trajectory checkpoints).
- **QS-3** No placeholder code. `TODO(...)` is only ever `TODO(review)` under §10.2.
- **QS-4** Comments, LEARN blocks, index, and KNOWN_LIMITATIONS travel in the same commit as the code they describe.
- **QS-5** A phase is not "done" when its code works; it is done when its gate block (commands + protocol + LEARN audit + git gate + phase report) is complete.

---

## 11. Risk register (top 5)

| # | Risk | Likelihood / Impact | Mitigation (where in plan) |
|---|------|--------------------|-----------------------------|
| R-1 | **Embabel API drift** between authoring (1.5.1) and execution | High / Medium | §2 capability matrix with citations and per-capability fallbacks; Task 0.3 re-verifies every row before agent code is written; §13.3 drift rule; C-5/C-6 fallbacks pre-declared as KL-08/KL-09 |
| R-2 | **Local-model structured-output weakness** (Ollama emits invalid JSON) | High / Medium | C-1 strict binding: malformed output becomes typed `ValidationRejection`, never an object (Task 3.2 test); native structured output mode where supported; `toolloop.*` retry knobs (C-8); mock mode is the CI gate, live floor is stated (D13) and applies per provider (KL-06) |
| R-3 | **Sandbox build flakiness** (Central reachability, Docker Desktop WSL2 integration, cache-volume staleness) | Medium / High | Named cache volume; hard timeouts with typed timeout result; `docker-it` profile isolates env-dependent tests; §13.3 environment notes; clean-clone reproduction (Task 7.3) proves the environment end-to-end |
| R-4 | **Planner non-termination** (looping replans burn budget) | Medium / High | Verified bound (C-7): `ProcessOptions.control = firstOf(maxActions(renovator.budget.max-actions, default 25), ON_STUCK)`; attempt ceiling per fixture in `expected-outcome.yml`; `fixture-no-path` + `BudgetEnforcementIT` prove the loop exits honestly (Task 4.4) |
| R-5 | **Diff-parser edge cases** (CRLF, binary, renames, context drift) | Medium / Medium | Real library (java-diff-utils, L2), adversarial unit tests + jqwik property tests (`DiffApplyPropertyTest`); binary/rename/deletion rejected by scope (KL-10); context mismatch rejection names the hunk so the planner gets a usable signal |

---

## 12. Out-of-scope list → pre-declared KNOWN_LIMITATIONS mapping

| Out of scope (never in this project) | Mapped entry |
|---|---|
| Gradle / Kotlin DSL fixture builds (D4) | KL-03 |
| Authentication/authorization on the control API; multi-tenant use | KL-02 |
| Concurrent runs / distributed execution | KL-01 |
| Treating LLM diagnosis text as evidence of correctness | KL-04 |
| Benchmark-grade evaluation (large N, statistical rigor) | KL-05 |
| Ollama performance guarantees | KL-06 |
| Defense against in-process reflection attacks (boundary is the LLM/planner path) | KL-07 |
| Guaranteed live-`AgentProcess` round-trip (resume may re-seed from `RunSnapshot`) | KL-08 |
| Rich HITL form rendering (shell-quality UI) in the REST API | KL-09 |
| Binary files, file renames, file deletions in patches | KL-10 |
| Windows-native or containerized dsh execution (D15 fixes WSL2-native) | KL-11 |
| Upgrading non-Maven targets, monorepos, multi-module reactor targets (single-module fixture scope) | KL-03 (same entry, stated in its rationale) |

---

## 13. Executor environment (dsh) — binding instructions

### 13.1 Mode
**Standard.** Full tool kit: file editing, shell, file/web search, skills, planning. **Not Creative mode** — the executor must not write or mount its own tools; self-made in-memory plugins vanish on restart, and tool-invention contradicts this project's deterministic-boundary philosophy.

### 13.2 Pre-flight checklist (Phase 0, Task 0.0 gate)
- **Context7 MCP** (or `ctx7` CLI skill) — **mandatory** for Embabel, Spring Boot 4, and any young/fast-moving dependency. Rule: *query current docs before writing any Embabel API call; training data is stale.* The §2 matrix is the map; Context7 is the territory check.
- **GitHub access:** official GitHub MCP or `gh` CLI — for reading Embabel examples/issues when Context7 is insufficient (e.g. `embabel/embabel-agent-examples`).
- **Web search:** dsh Standard built-in — for Maven Central/release verification.
- **Runtime topology (D15):** everything executes natively in WSL2 at `~/dev2/Renovator`; `docker` CLI must resolve to Docker Desktop's WSL2 integration (`docker version` check); Ollama, when used, runs Windows-side with `OLLAMA_HOST=0.0.0.0`.

### 13.3 Version verification rule (binding)
Every version pinned in §3 is re-verified at execution time against Maven Central / official docs **before first use in a phase gate**. If a pin is unavailable or incompatible: bump minimally → re-run the phase's verify command → record an **environment note** in the phase report (what changed, why, evidence link). Embabel is young: assume API drift between authoring and execution; absorb it through the §2 fallbacks; never redesign silently.

### 13.4 Trajectory hygiene
dsh's append-only Trajectory log is the execution audit. Each phase report (Appendix B) references trajectory checkpoints for every gate command and every Demonstration line. A gate without a trajectory checkpoint reference is not passed.

---

## 14. Phase order, parallelization, hard gates

```
Phase 0  Scaffold & toolchain            ── gate 0 ──┐
Phase 1  Fixtures & sandbox runner       ── gate 1 ──┤   (judge exists before the judged)
Phase 2  Domain model & validation       ── gate 2 ──┤
Phase 3  Agent core (happy path)         ── gate 3 ──┤
Phase 4  Replanning & states (centerpiece)── gate 4 ──┤
Phase 5  API, SSE & HITL                 ── gate 5 ──┤
Phase 6  Evals & observability           ── gate 6 ──┤
Phase 7  Docs & final audit              ── gate 7 ──┘
```

Hard serial dependency: each phase gate must pass before the next phase's first commit (GW-2/GW-3). Within phases, dsh may interleave these independent task pairs (all tasks still land one commit each):

- **Phase 1:** 1.1–1.4 (fixture projects) ∥ 1.6 (runner core: `WorkspaceCopier`, `Excerpt`, `BuildResultParser`); they meet at `DockerSandboxRunnerIT`.
- **Phase 2:** 2.3 ∥ 2.4 ∥ 2.5 (validators 1–3 are independent pure functions); they meet at 2.6 (L4 composes them) and 2.7 (executor boundary).
- **Phase 3:** 3.1 (shell + deterministic actions) ∥ 3.2-prep (prompt files + canned responses); meet at LLM action wiring.
- **Phase 5:** 5.2 (SSE) ∥ 5.3 (HITL gate) after 5.1 (controller skeleton) lands.
- **Phase 6:** 6.3 (metrics) ∥ 6.4 (trajectory query) after 6.1 (eval harness) lands.

Every phase ends with the identical gate block (varies only in the phase-specific commands):

> **Phase gate N**
> 1. Gate commands (listed per phase below) — all green, output pasted into phase report with trajectory checkpoint refs.
> 2. Protocol check: `python3 scripts/check_protocols.py --phase-boundary` exits 0.
> 3. LEARN audit: index ↔ code markers consistent; any new LEARN meets the restate test (self-assessed in the report).
> 4. Git gate: `git status --porcelain` empty; `git tag -a phase-N-complete` created with gate evidence in the message.
> 5. Phase report written to `docs/phase-reports/phase-N.md` per Appendix B (quotes one LEARN in full + restate-test self-assessment + all commit hashes + environment notes).

---

## 15. Phases 0–7 (tasks, files, tests, acceptance, demonstrations, LEARN placements)

Conventions: **Files** lists create-paths (repo-relative). **Tests** names test classes and methods exactly as they must appear (Kotlin backtick names). **Acceptance** is objective and checkable. **Demonstration** = command + observable result (QS-2: paste output into the phase report). Test profiles: unit tests run under `./mvnw verify`; `*IT` classes run under `-Pdocker-it` (Docker required) or `-Pllm-it` (live LLM required) per §10.5.

---

### Phase 0 — Scaffold & toolchain

**Goal:** `./mvnw verify` green on a walking skeleton; protocol machinery load-bearing from the first commit; dual-provider LLM path proven; every §2 capability re-verified before any agent code.
**Star skill:** none yet — this phase builds the stage. (Protocol and environment discipline are the deliverable.)

#### Task 0.0 — Materialize plan, repo init, executor pre-flight
- **Files:** `PLAN.md` (this file, copied verbatim), `.gitignore` (`target/`, `var/runs/*` except `.gitkeep`, `*.log`), initial `git init` at `~/dev2/Renovator` (WSL2-native, D15).
- **Pre-flight (§13.2):** `docker version` resolves to Docker Desktop WSL2 integration; `mvn -v` on Java 25; Context7 reachable; record all three outputs.
- **Tests:** none (chore commit). **Acceptance:** repo exists on WSL2 fs (`pwd` shows `/home/...`, not `/mnt/c`); pre-flight outputs pasted into phase report.
- **Demonstration:** `pwd && docker version --format '{{.Server.Os}}/{{.Server.Arch}}'` → `linux/amd64`; path contains no `/mnt/c`.

#### Task 0.1 — Maven/Kotlin/Spring Boot skeleton
- **Files:** `pom.xml` (parent `spring-boot-starter-parent:4.1.1`; `<java.version>25</java.version>`; Kotlin per §3 rule; deps: `spring-boot-starter-web`, `spring-boot-starter-actuator`, `com.embabel.agent:embabel-agent-starter:1.5.1`, `embabel-agent-starter-openai:1.5.1`, `embabel-agent-starter-openai-custom:1.5.1`, test: `spring-boot-starter-test`, `embabel-agent-test-support:1.5.1`, `net.jqwik:jqwik:1.10.1`; kotlin-maven-plugin with `-Xjdk-release=25` / jvmTarget aligned; surefire configured to exclude `*IT` by default and honor profiles `docker-it`, `llm-it`), `mvnw` + `.mvn/wrapper` (Maven 3.9.x), `src/main/kotlin/com/renovator/RenovatorApplication.kt`, `src/main/resources/application.yml`, `src/test/kotlin/com/renovator/RenovatorApplicationTests.kt`.
- **Tests:** `RenovatorApplicationTests.contextLoads`; `RenovatorApplicationTests.actuator health endpoint is up` (`@SpringBootTest(webEnvironment=RANDOM_PORT)`).
- **Acceptance:** `./mvnw verify` → BUILD SUCCESS; dependency tree resolves with no `com.embabel` version conflicts (`./mvnw -q dependency:tree -Dincludes=com.embabel.agent` printed into report).
- **Demonstration:** `./mvnw -q verify && echo GATE-OK` → `GATE-OK`.

#### Task 0.2 — ktlint, pinned and bound to verify
- **Files:** `pom.xml` (add `com.github.gantsign.maven:ktlint-maven-plugin:3.7.1`, `check` goal bound to `verify`), `scripts/verify-ktlint-gate.sh` (misformats a scratch copy, asserts the build fails, asserts clean tree passes).
- **Tests:** the script *is* the test (negative + positive). **Acceptance:** misformatted code fails `./mvnw verify`; formatted passes.
- **Demonstration:** `scripts/verify-ktlint-gate.sh` → prints `FAIL confirmed (expected)` then `PASS confirmed`.

#### Task 0.3 — Embabel capability re-verification + minimal agent shell
- **Files:** `docs/verification-log.md` (table: capability C-1…C-10, doc URL consulted at execution time, result, drift note if any), `src/main/kotlin/com/renovator/agent/RenovatorAgent.kt` (minimal: `@Agent(description = "…")` with one deterministic `@Action fun echoGoal(goal: UpgradeGoalStub): GoalAcknowledged` + one `@AchievesGoal @Action`; stub types local to this task and replaced in Task 2.1 as **planned work** — stated here and there, so no `TODO(review)` marker is needed or allowed).
- **Method (mandatory):** for each §2 row, re-query Context7 `/embabel/embabel-agent` and/or the cited GitHub path; paste the confirming excerpt into `verification-log.md`. If a row cannot be confirmed: apply the row's fallback and record it — **before** writing any dependent code.
- **Tests:** `AgentShellWiringTest.agent metadata builds from annotations` (`AgentMetadataReader` + `IntegrationTestUtils.dummyAgentPlatform()` per C-9); `AgentShellWiringTest.echo action runs end to end on dummy platform` (`agentProcess.run().resultOfType(GoalAcknowledged::class.java)`).
- **Acceptance:** verification log has 10 rows, all `CONFIRMED` or `FALLBACK <reason>`; both tests green.
- **Demonstration:** `./mvnw -q test -Dtest=AgentShellWiringTest && echo VERIFIED` → `VERIFIED`.

#### Task 0.4 — Config system (dual LLM provider, sandbox, validation rules)
- **Files:** `src/main/kotlin/com/renovator/config/RenovatorProperties.kt` (`@ConfigurationProperties("renovator")`: `llm{provider, baseUrl, apiKey, model, plannerRole}`, `sandbox{image, timeoutSeconds, memoryMb, cpus, cacheVolume}`, `validation{allowedPaths, forbiddenPaths, allowedRepositories, allowSnapshots, dryRunCompile}`, `approvals{plan, commitCandidate}`, `budget{maxActions}` with validation annotations), `src/main/kotlin/com/renovator/config/LlmProviderConfig.kt` (maps `LLM_PROVIDER=cloud|ollama` to Embabel model config: cloud → `embabel-agent-starter-openai` with `OPENAI_BASE_URL` optional; ollama → `openai-custom` base-url `${LLM_BASE_URL:http://localhost:11434}/v1` + `models:` list + `embabel.models.default-llm` set from `renovator.llm.model`; **no code path branches on provider**), `application.yml` (env-var wiring; `embabel.agent.platform.toolloop.*` small-model knobs per C-8, commented).
- **Tests:** `RenovatorPropertiesTest.binds defaults with no env vars`; `RenovatorPropertiesTest.rejects sandbox timeout below 10 seconds`; `RenovatorPropertiesTest.rejects unknown provider value`; `LlmProviderConfigTest.ollama provider yields openai-custom base url and default-llm without code change`; `LlmProviderConfigTest.cloud provider yields OPENAI base url path`.
- **LEARN:** **LEARN[001]** on `LlmProviderConfig.kt` — one client abstraction, two providers: the OpenAI-compatible trick, why `OLLAMA_HOST=0.0.0.0` matters from WSL2 (D15), why the switch is env-only.
- **Acceptance:** all tests green; `grep -rn "when.*provider" src/main/kotlin` shows no provider-branching logic outside config binding.
- **Demonstration:** `LLM_PROVIDER=ollama LLM_BASE_URL=http://localhost:11434 ./mvnw -q test -Dtest=LlmProviderConfigTest && echo DUAL-OK` → `DUAL-OK`.

#### Task 0.5 — Dual-provider LLM smoke test
- **Files:** `src/main/kotlin/com/renovator/llm/LlmSmokeService.kt` (injects `Ai`; `createObject` into `data class PingResponse(val answer: String)`), `src/test/kotlin/com/renovator/llm/LlmConnectivitySmokeIT.kt` (`llm-it` profile; skipped unless `LLM_SMOKE=1`).
- **Tests:** `LlmConnectivitySmokeIT.returns a bound PingResponse from the configured provider`.
- **Acceptance:** with cloud creds: `LLM_PROVIDER=cloud LLM_SMOKE=1 ./mvnw -Pllm-it test` green; with Ollama Windows-side (`OLLAMA_HOST=0.0.0.0 ollama serve`): `LLM_PROVIDER=ollama LLM_BASE_URL=http://localhost:11434 LLM_SMOKE=1 ./mvnw -Pllm-it test` green — **same test, zero code change**. Both runs pasted into the phase report (env note if Ollama not on hardware — KL-06 applies).
- **Demonstration:** the two commands above; each prints the bound `PingResponse`.

#### Task 0.6 — Protocol tooling (checker, hook, index, limitations seeds)
- **Files:** `scripts/check_protocols.py` (Appendix C spec), `scripts/install_hooks.sh`, `scripts/test_check_protocols.py` (stdlib `unittest`, fixture strings inline), `docs/protocol.md` (§10 rendered into repo docs), `LEARN_INDEX.md` (skeleton table), `KNOWN_LIMITATIONS.md` (KL-01…KL-06 seeds from §10.2).
- **Tests:** `test_check_protocols.py`: `accepts valid learn block`; `rejects missing field`; `rejects numbering gap`; `rejects orphan todo-review marker`; `rejects orphan limitations entry`; `accepts matched marker and entry`; `rejects prompts outside prompts dir when phase >= 3`.
- **LEARN:** **LEARN[002]** on `check_protocols.py` header — why the lint is mechanical and load-bearing (protocols enforced by review drift; protocols enforced by hooks hold).
- **Acceptance:** `scripts/install_hooks.sh` → `.git/hooks/pre-commit` present and executable; a scratch commit violating the protocol is **rejected** with named errors; a clean commit passes; `python3 scripts/test_check_protocols.py` green.
- **Demonstration:** paste the rejected commit's hook output (lists the violations) into the phase report.

**Phase gate 0**
1. `./mvnw verify` green; `scripts/verify-ktlint-gate.sh` green; `python3 scripts/test_check_protocols.py` green; `python3 scripts/check_protocols.py --phase-boundary` exits 0.
2. Protocol check + LEARN audit (LEARN[001], LEARN[002] present, indexed, restate-test self-assessed).
3. Git gate: clean tree; `git tag -a phase-0-complete` with gate evidence.
4. Phase report `docs/phase-reports/phase-0.md` per Appendix B; environment notes (versions actually resolved; Ollama availability).

---

### Phase 1 — Fixture repos & sandbox build runner

**Goal:** the deterministic judge exists and is trusted **before any agent code is written**. Four fixtures with known outcomes; a sandboxed runner producing typed `BuildResult`.
**Star skill:** none directly — but this phase *is* "deterministic judge + cheap reversibility," the property the whole trilogy claim rests on.

#### Task 1.1 — `fixture-clean`
- **Files:** `fixtures/fixture-clean/pom.xml` (Java 17 release, `commons-lang3:3.12.0`, JUnit 5, surefire), `fixtures/fixture-clean/src/main/java/com/example/clean/StringTools.java`, `fixtures/fixture-clean/src/test/java/com/example/clean/StringToolsTest.java`, `fixtures/fixture-clean/expected-outcome.yml` (per §8.1).
- **Tests:** `FixtureSanityTest.fixture-clean baseline builds green` (invokes `mvn -q -f fixtures/fixture-clean verify` — authoring-time only; the runtime never builds in place); `FixtureSanityTest.fixture-clean expected-outcome parses`.
- **Acceptance:** baseline green; YAML matches §8.1 exactly.
- **Demonstration:** `mvn -q -f fixtures/fixture-clean verify && echo CLEAN-GREEN` → `CLEAN-GREEN`.

#### Task 1.2 — `fixture-api-removal`
- **Files:** same shape as 1.1 under `fixtures/fixture-api-removal/`; code calls `org.apache.commons.lang.StringEscapeUtils.escapeSql`; dep `commons-lang:commons-lang:2.6`; `expected-outcome.yml` per §8.2; `fixtures/fixture-api-removal/README.md` documenting the API-removal mechanism.
- **Tests:** `FixtureSanityTest.fixture-api-removal baseline builds green`; `FixtureSanityTest.after manual coordinate swap the build fails naming escapeSql` (scripted: copy to temp dir, sed pom to `commons-lang3:3.14.0`, `mvn -q compile` must fail, output must contain `escapeSql` — this is the breakage the agent will face).
- **Acceptance:** the failure is deterministic and the error names the symbol; YAML matches §8.2.
- **Demonstration:** the scripted swap prints `cannot find symbol … escapeSql` (exact compiler line pasted into report).

#### Task 1.3 — `fixture-transitive-conflict`
- **Files:** `fixtures/fixture-transitive-conflict/pom.xml` (guava `32.1.2-jre` direct, guice `7.0.0` fixed, enforcer `3.6.3` `dependencyConvergence` on `validate`), one trivial class using `ImmutableList.copyOf`, test, `expected-outcome.yml` per §8.3, `README.md` with the `dependency:tree` evidence.
- **Mandatory verification (per §8.3):** baseline `mvn -q validate` green; after direct-bump edit, `mvn -q validate` fails with `dependencyConvergence` naming guava; paste `dependency:tree -Dincludes=com.google.guava:guava` into the fixture README. If drifted: alternate sets B/C per §8.3 + environment note.
- **Tests:** `FixtureSanityTest.fixture-transitive-conflict baseline validates green`; `FixtureSanityTest.direct bump deterministically fails convergence naming guava`.
- **Acceptance:** both behaviors deterministic across two consecutive runs (flaky judge = phase gate fails).
- **Demonstration:** `mvn -q -f fixtures/fixture-transitive-conflict validate && echo BASE-GREEN` → `BASE-GREEN`; post-bump run exits non-zero with the enforcer message.

#### Task 1.4 — `fixture-no-path`
- **Files:** `fixtures/fixture-no-path/` (clean pom), goal `commons-lang3 → 99.99.99` + `NoSnapshots`, `expected-outcome.yml` per §8.4.
- **Tests:** `FixtureSanityTest.fixture-no-path baseline builds green`; `FixtureSanityTest.target version 99.99.99 does not exist on central` (HTTP HEAD → 404).
- **Acceptance:** YAML terminal state is `UpgradeBlocker`, `mustNotVisitStages: [Applying]`.
- **Demonstration:** `curl -s -o /dev/null -w '%{http_code}' https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/99.99.99/commons-lang3-99.99.99.pom` → `404`.

#### Task 1.5 — Outcome schema + fixtures README (eval dataset, D13)
- **Files:** `src/main/kotlin/com/renovator/eval/ExpectedOutcome.kt` (data classes matching §8 schema, Jackson YAML), `fixtures/README.md` (fixture catalog + how breakage is injected + why the judge precedes the judged).
- **Tests:** `OutcomeYamlSchemaTest.parses all four fixtures and validates their fields`; `OutcomeYamlSchemaTest.rejects unknown terminal state`; `OutcomeYamlSchemaTest.rejects maxAttempts below 1`.
- **LEARN:** **LEARN[003]** in `fixtures/README.md` — judge-before-judged: why deterministic breakage fixtures land before agent code, and why their expected outcomes double as the eval dataset.
- **Acceptance:** all four YAMLs parse; invalid fixture YAML fails the build.
- **Demonstration:** `./mvnw -q test -Dtest=OutcomeYamlSchemaTest && echo DATASET-OK` → `DATASET-OK`.

#### Task 1.6 — Sandbox build runner (D7)
- **Files:** `src/main/kotlin/com/renovator/execution/WorkspaceCopier.kt` (pristine temp copy; excludes `target/`, `.git/`; preserves timestamps off), `src/main/kotlin/com/renovator/execution/Excerpt.kt` (head/tail truncation per §8.5), `src/main/kotlin/com/renovator/execution/BuildResultParser.kt` (extracts `failedGoals` `[plugin:goal]` strings from Maven output), `src/main/kotlin/com/renovator/execution/DockerSandboxRunner.kt` (`fun runBuild(workspace: WorkspaceRef, goals: List<String>, timeout: Duration): BuildResult`; Docker CLI per §8.5; kills on timeout), `src/test/resources/buildlogs/enforcer-failure.log`, `compile-failure.log` (real captured samples).
- **Tests:** `WorkspaceCopierTest.copies tree excluding target and dot-git`; `WorkspaceCopierTest.source tree hashes unchanged after copy`; `ExcerptTest.truncates middle preserving head and tail within budget`; `BuildResultParserTest.parses failed plugin goal from enforcer log sample`; `BuildResultParserTest.parses compile failure from javac log sample`; `DockerSandboxRunnerIT` (`docker-it`): `runs fixture-clean green and reports durationMs above zero`; `captures compile failure from api-removal variant`; `kills runaway container at hard timeout` (uses the runner's internal command hook with `sleep 600`, documented as a test-only seam); `never mutates the source fixture directory` (hash before/after).
- **LEARN:** **LEARN[004]** on `DockerSandboxRunner.kt` — reversibility (throwaway container + pristine copy) as the property that makes agent retries safe; why Docker CLI over Testcontainers; `Excerpt` budget rationale (LLM context is finite; the judge keeps the full log on disk).
- **Acceptance:** unit tests green in `./mvnw verify`; `DockerSandboxRunnerIT` green under `-Pdocker-it`; timeout test proves the kill.
- **Demonstration:** `./mvnw -q -Pdocker-it test -Dtest=DockerSandboxRunnerIT | grep -E "Tests run|durationMs"` → `Tests run: 4, Failures: 0` and a positive `durationMs`.

**Phase gate 1**
1. `./mvnw verify` green AND `./mvnw -q -Pdocker-it verify` green.
2. Protocol check; LEARN audit (LEARN[003], LEARN[004]).
3. Git gate: clean tree; `git tag -a phase-1-complete`.
4. Phase report incl. the two-run determinism evidence from 1.3 and the `dependency:tree` paste.

---

### Phase 2 — Domain model & validation pipeline

**Goal:** every blackboard type exists; validators L1–L4 are pure functions with adversarial tests; the executor boundary is sealed and the signature test proves it.
**Star skill #1: bridging non-deterministic AI with deterministic code.**

#### Task 2.1 — Proposal types + strict Jackson boundary
- **Files:** `src/main/kotlin/com/renovator/domain/Proposals.kt` (all §5 proposal types), `src/main/kotlin/com/renovator/config/JacksonConfig.kt` (Kotlin module; `FAIL_ON_UNKNOWN_PROPERTIES` on for the proposal package); removes the Task-0.3 stub types (planned replacement, stated in Task 0.3).
- **Tests:** `ProposalTypesTest.every proposal type round-trips through Jackson`; `ProposalTypesTest.rejects unknown keys on deserialize`; `ProposalTypesTest.PlanStep sealed hierarchy serializes with type tag`; `ProposalTypesTest.rejects blank groupId or version` (validation annotations / `require`).
- **LEARN:** **LEARN[005]** header essay on `Proposals.kt` — Kotlin for a Java engineer: data class ≈ record, sealed interface ≈ sealed interface + pattern-matching `when`, null-safety vs `Optional`, why `val` + immutability is the blackboard's friend.
- **Acceptance:** tests green; Task-0.3 stubs gone (`grep -rn UpgradeGoalStub src/main` → no matches).
- **Demonstration:** `./mvnw -q test -Dtest=ProposalTypesTest && echo DOMAIN-OK` → `DOMAIN-OK`.

#### Task 2.2 — Result types, `Excerpt` reuse, stage hierarchy (pre-`@State`)
- **Files:** `src/main/kotlin/com/renovator/domain/Results.kt` (all §5 result types incl. `ValidationRejection`, `UpgradeBlocker`, `AttemptRecord`, `UpgradeComplete`), `src/main/kotlin/com/renovator/domain/Stages.kt` (plain sealed `UpgradeStage`; `@State` wiring arrives in Phase 4).
- **Tests:** `ResultTypesTest.validation rejection carries check name reason and offending content`; `ResultTypesTest.upgrade blocker requires non-empty attempts and human question`; `StageHierarchyTest.when over UpgradeStage is exhaustive` (compile-time proof + serialization round-trip).
- **Acceptance:** tests green; no executor code exists yet (boundary lands in 2.7).
- **Demonstration:** `./mvnw -q test -Dtest='ResultTypesTest,StageHierarchyTest' && echo RESULTS-OK` → `RESULTS-OK`.

#### Task 2.3 — Layer 1: `PathWhitelistValidator`
- **Files:** `src/main/kotlin/com/renovator/validation/PathWhitelistValidator.kt` (`fun check(patch: CodePatch): ValidationRejection?`; normalize-then-match per §7 L1; config-driven allow/forbid lists).
- **Tests:** `PathWhitelistValidatorTest.rejects dot-dot escape even when a later glob would allow it`; `.rejects absolute path`; `.rejects dot-git path despite wildcard allow`; `.rejects shell script under src`; `.rejects env file at any depth`; `.accepts pom dot-xml at root`; `.accepts new file under src main java`; `.normalizes windows separators and redundant dots`. Property: `PathWhitelistPropertyTest.any path matching a forbidden pattern after normalization is rejected` (jqwik, ≥ 1000 tries, arbitrary mix of separators/dot-segments).
- **LEARN:** **LEARN[007]** — normalize-then-match: why matching before normalizing is the classic whitelist bypass.
- **Acceptance:** unit + property tests green.
- **Demonstration:** `./mvnw -q test -Dtest='PathWhitelistValidatorTest,PathWhitelistPropertyTest' && echo L1-OK` → `L1-OK`.

#### Task 2.4 — Layer 2: `DiffApplyValidator`
- **Files:** `src/main/kotlin/com/renovator/validation/DiffApplyValidator.kt` (java-diff-utils 4.17; in-memory apply; hunk-naming rejections; binary/rename/deletion rejected — KL-10 marker + entry added in this commit).
- **Tests:** `DiffApplyValidatorTest.accepts diff that applies cleanly`; `.rejects hunk whose context does not match, naming hunk index and expected line`; `.rejects modification of nonexistent file`; `.accepts new-file diff`; `.rejects binary diff by scope`; `.rejects rename-only diff by scope`; `.rejects malformed diff header`. Property: `DiffApplyPropertyTest.no generated corrupted diff applies to its target` (mutate context lines arbitrarily → must be rejected).
- **LEARN:** **LEARN[008]** — a real diff library, never regex: what unified-diff context lines are *for* (they are the deterministic judge of "does this patch still apply").
- **Acceptance:** unit + property green.
- **Demonstration:** `./mvnw -q test -Dtest='DiffApplyValidatorTest,DiffApplyPropertyTest' && echo L2-OK` → `L2-OK`.

#### Task 2.5 — Layer 3: `DomainInvariantValidator` + `VersionCatalog`
- **Files:** `src/main/kotlin/com/renovator/validation/VersionCatalog.kt` (interface + `FakeVersionCatalog` test impl), `src/main/kotlin/com/renovator/validation/HttpVersionCatalog.kt` (HEAD repo1; connect/read timeouts; no retries — the planner's replan is the retry), `src/main/kotlin/com/renovator/validation/DomainInvariantValidator.kt` (existence, monotonic increase via `ComparableVersion`, snapshot policy, post-edit pom re-parse via Maven Model API, repository allowlist). `pom.xml` adds `maven-model:3.9.16`, `maven-artifact:3.9.16`.
- **Tests:** `DomainInvariantValidatorTest.rejects version that does not exist in the catalog`; `.rejects downgrade even when version exists`; `.rejects snapshot when disallowed`; `.accepts snapshot when constraint allows`; `.rejects pom missing modelVersion after edit`; `.rejects pom adding repository outside allowlist`; `.accepts clean version bump`. `HttpVersionCatalogIT` (`docker-it`-free, `@Tag("network")`, runs in `verify`): `.commons-lang3 3.14.0 exists on central` (404/200 assertions).
- **Acceptance:** hermetic tests use the fake catalog; exactly one network test proves the real wiring.
- **Demonstration:** `./mvnw -q test -Dtest='DomainInvariantValidatorTest,HttpVersionCatalogIT' && echo L3-OK` → `L3-OK`.

#### Task 2.6 — Layer 4: `DryRunCompileValidator`
- **Files:** `src/main/kotlin/com/renovator/validation/DryRunCompileValidator.kt` (composes `WorkspaceCopier` + `DockerSandboxRunner`; parses `CompileError(file, line, col, message)` from javac output; honors `renovator.validation.dry-run-compile` toggle per D9).
- **Tests:** `CompileErrorParserTest.parses javac error lines into typed errors`; `DryRunCompileValidatorIT` (`docker-it`): `.rejects the api-removal breakage, naming StringTools.java and escapeSql`; `.accepts a benign patch on fixture-clean`; `DryRunCompileValidatorTest.toggle off short-circuits to skipped result`.
- **Acceptance:** rejection output contains file + symbol; toggle works.
- **Demonstration:** `./mvnw -q -Pdocker-it test -Dtest=DryRunCompileValidatorIT && echo L4-OK` → `L4-OK`.

#### Task 2.7 — Executor boundary + **signature test**
- **Files:** `src/main/kotlin/com/renovator/validation/Validated.kt` (`ValidatedPlan`, `ValidatedPatch`, `ValidationProof` — internal constructors, digest-bound), `src/main/kotlin/com/renovator/execution/UpgradeExecutor.kt` (`fun apply(plan: ValidatedPlan, workspace: WorkspaceRef): ExecutionReceipt`; proof recomputation; `UnvalidatedProposalException`), `src/main/kotlin/com/renovator/execution/ExecutionReceipt.kt`. KL-07 marker + entry added in this commit.
- **Tests (signature test, §7.7, quoted in the gate):** `ExecutorBoundaryTest.rejects raw CodePatch JSON POSTed as ValidatedPatch`; `.rejects forged proof whose digest does not match the payload`; `.rejects proof whose checkNames omit mandatory layers`; `.rejects a ValidatedPatch constructed by reflection with a garbage proof`; `.every public method of UpgradeExecutor declares only Validated-star parameter types` (reflection). Plus `UpgradeExecutorTest.applies a genuinely validated plan to a workspace copy`.
- **LEARN:** **LEARN[006]** on `UpgradeExecutor.kt` — the enforcement-boundary principle: validation is code, not prompts; why type-sealing + digest-bound proofs; what the boundary does *not* defend (reflection, KL-07) and why that's the right scope.
- **Acceptance:** signature test green; `grep -rn "fun apply(" src/main/kotlin/com/renovator/execution` shows only `Validated*` parameter types.
- **Demonstration:** `./mvnw -q test -Dtest='ExecutorBoundaryTest,UpgradeExecutorTest' && echo BOUNDARY-HOLDS` → `BOUNDARY-HOLDS`.

**Phase gate 2**
1. `./mvnw verify` green AND `./mvnw -q -Pdocker-it verify` green (L4 ITs included).
2. Protocol check; LEARN audit (LEARN[005]–LEARN[008], incl. mandatory Kotlin essay + boundary essay).
3. Git gate: clean tree; `git tag -a phase-2-complete`.
4. Phase report: signature test output quoted in full; KL-07 and KL-10 openings listed.

---

### Phase 3 — Agent core

**Goal:** the Embabel agent runs the palette end-to-end; happy path green on `fixture-clean` with a mock LLM; trajectory persisted from the first run.
**Star skill #2 begins: dynamic planning (wiring + costs); skill #3 begins: blackboard trajectory.**

#### Task 3.1 — Agent shell + deterministic actions
- **Files:** rewrite `src/main/kotlin/com/renovator/agent/RenovatorAgent.kt` onto the real domain (goal `BuildGreen` via `@AchievesGoal` on `finalizeUpgrade`; GOAP default planner), `src/main/kotlin/com/renovator/agent/actions/AnalyzeRepositoryAction.kt` (deterministic: Maven Model API parse → `RepoModel`), `src/main/kotlin/com/renovator/agent/actions/RunBuildAction.kt` (wraps `DockerSandboxRunner`), `src/main/kotlin/com/renovator/agent/actions/ApplyValidatedChangesAction.kt` (the **only** action calling `UpgradeExecutor`), `src/main/kotlin/com/renovator/agent/actions/FinalizeUpgradeAction.kt`.
- **Tests:** `AnalyzeRepositoryActionTest.parses fixture-clean into RepoModel with commons-lang3 3.12.0`; `AnalyzeRepositoryActionTest.detects enforcer convergence rule in fixture-transitive-conflict`; `AgentPaletteCompletenessTest.every palette action in the plan table exists with explicit precondition and output type` (reflection over `AgentMetadataReader` output; the §6 table is the oracle); `AgentPaletteCompletenessTest.only ApplyValidatedChangesAction references UpgradeExecutor`.
- **LEARN:** **LEARN[011]** on `AnalyzeRepositoryAction.kt` — the typed blackboard ≈ process variables in a workflow engine; type-driven binding (latest-of-type wins) and what that implies for design.
- **Acceptance:** tests green; palette table and code provably in lockstep.
- **Demonstration:** `./mvnw -q test -Dtest='AnalyzeRepositoryActionTest,AgentPaletteCompletenessTest' && echo PALETTE-OK` → `PALETTE-OK`.

#### Task 3.2 — LLM actions with typed binding (D6, C-1)
- **Files:** `src/main/resources/prompts/propose_plan.st`, `diagnose_failure.st`, `propose_patch.st` (the only prompt files; versioned), `src/main/kotlin/com/renovator/agent/actions/ProposeUpgradePlanAction.kt`, `DiagnoseFailureAction.kt`, `ProposePatchAction.kt` — all use `context.ai().withLlmByRole("planner")…createObject(...)`; each carries `.withExample(...)` from canned fixtures; each wraps binding failure into a typed `ValidationRejection` (never throws raw).
- **Tests (FakeOperationContext, C-9):** `ProposeUpgradePlanActionTest.binds typed plan from canned response`; `.produces two-hop plan when canned response has management-scope step`; `DiagnoseFailureActionTest.extracts root cause list from canned diagnosis`; `ProposePatchActionTest.binds patch with unified diff intact`; **`LLMBindingStrictnessTest.garbage llm output becomes a typed ValidationRejection, never a blackboard object`** (FakePromptRunner emits non-JSON / extra-key JSON / wrong-type JSON — three cases).
- **LEARN:** **LEARN[009]** on `RenovatorAgent.kt` — **the canonical essay:** GOAP/dynamic planning vs Sentinel's static LangGraph wiring: what the planner buys (replanning around typed failure from the same palette), what it costs (you must design preconditions, not edges), and why validation-by-type makes dynamic safe where it would otherwise be unreviewable.
- **Acceptance:** strictness test proves Layer 0; prompts only under `resources/prompts/` (checker now hard-fails this).
- **Demonstration:** `./mvnw -q test -Dtest='ProposeUpgradePlanActionTest,DiagnoseFailureActionTest,ProposePatchActionTest,LLMBindingStrictnessTest' && echo LLM-BOUND` → `LLM-BOUND`.

#### Task 3.3 — Action costs & preconditions (D9, C-4)
- **Files:** cost/precondition annotations on all actions per the §6 table; `src/main/kotlin/com/renovator/agent/conditions/CommitCandidacyCondition.kt` (`@Condition` — true when tests not yet green but a `Validated*` awaits commit-candidacy; gates `dryRunCompile`), `src/main/kotlin/com/renovator/agent/conditions/GateArmedCondition.kt` (approval gates from config).
- **Tests:** `ActionCostTableTest.every action declares a cost matching the plan table`; `ActionCostTableTest.expensive actions (sandbox) declare cost at least 0.6`; `PlannerOrderingIT.on the happy path, cheap validators run before any sandbox build` (dummy-platform run with scripted outcomes; assert action-history order).
- **LEARN:** **LEARN[010]** — cost asymmetry: the planner prefers plans that fail cheap; costs are guidance, never correctness.
- **Acceptance:** ordering IT green; cost table enforced by reflection.
- **Demonstration:** `./mvnw -q test -Dtest='ActionCostTableTest,PlannerOrderingIT' && echo COSTS-OK` → `COSTS-OK`.

#### Task 3.4 — Happy path end-to-end on `fixture-clean` (mock LLM)
- **Files:** `src/main/kotlin/com/renovator/audit/TrajectoryEvent.kt` (sealed: `StageEntered`, `ProposalReceived`, `ValidationOutcome`, `PlanAttempted`, `BuildObserved`, `Escalated`, `Completed`), `src/main/kotlin/com/renovator/audit/TrajectoryStore.kt` (JSONL append per run at `var/runs/{runId}/trajectory.jsonl`; hooked via `AgentProcessCallback`/action wrappers), `src/main/kotlin/com/renovator/audit/RunRegistry.kt` (single-run-at-a-time enforcement — KL-01 marker), `eval/canned/fixture-clean/propose_plan.json` (canned single-step plan).
- **Tests:** `TrajectoryStoreTest.appends typed events in insertion order`; `TrajectoryStoreTest.last line survives an interrupted write intact`; `RunRegistryTest.second concurrent run is rejected`; `HappyPathUpgradeIT` (`docker-it`, mock LLM via `EmbabelMockitoIntegrationTest`): `.fixture-clean upgrade reaches UpgradeComplete with exactly one build`; `.trajectory contains stages Analyzing, Planning, Applying, Verifying in order`; `.no ValidationRejection appears on the happy path`.
- **Acceptance:** IT green; trajectory file exists and validates against `expected-outcome.yml` fields.
- **Demonstration:** `./mvnw -q -Pdocker-it test -Dtest=HappyPathUpgradeIT && cat var/runs/*/trajectory.jsonl | head -20` → stages in order, terminal `Completed`.

#### Task 3.5 — Prompt/version hygiene checkpoint
- **Files:** none new; audit commit.
- **Tests:** `PromptLocationTest.no prompt-shaped literals exist outside resources prompts` (mirrors checker rule 4 at build time).
- **Acceptance:** `./mvnw verify` green; checker clean.
- **Demonstration:** `python3 scripts/check_protocols.py` → `0 violations`.

**Phase gate 3**
1. `./mvnw verify` AND `./mvnw -q -Pdocker-it verify` green.
2. Protocol check; LEARN audit (LEARN[009] canonical essay present and quoted in the report; LEARN[010], LEARN[011]).
3. Git gate: clean tree; `git tag -a phase-3-complete`.
4. Phase report: happy-path trajectory excerpt; palette table ↔ reflection test cross-reference.

---

### Phase 4 — Replanning & states (the centerpiece)

**Goal:** the propose → validate → execute → observe → replan loop is demonstrable under deterministic breakage; `@State` loops carry the lifecycle; plan-space exhaustion escalates honestly; the process survives a kill.
**Star skills #2 and #3: error recovery via dynamic replanning; state management across reasoning cycles.**

#### Task 4.1 — `@State` hierarchy wired (C-2)
- **Files:** rewrite `src/main/kotlin/com/renovator/domain/Stages.kt` → `src/main/kotlin/com/renovator/agent/states/Stages.kt`: `@State sealed interface UpgradeStage` + top-level data classes `Analyzing`, `Planning`, `Applying`, `Verifying`, `Repairing`, `Blocked`, `Done`; loop-carried data (goal, repoModel, current plan, attempt list) travels **in the state instances** (per the verified doc: looping + `clearBlackboard = true` requires it); looping actions `Applying → Verifying → Repairing → Applying` annotated `@Action(clearBlackboard = true)`; `Done` holds the `@AchievesGoal` action producing `UpgradeComplete`. Entry action (`analyzeRepository`) returns `Analyzing`, making the stage machine planner-visible.
- **Tests:** `StateLoopTest.repairing loops back to applying and terminates within budget` (dummy platform, scripted build outcomes fail-fail-pass); `StateScopingTest.only the current state's actions are plannable while in Repairing`; `StateCarriedDataTest.attempt records survive the loop because they ride the state instance`.
- **LEARN:** **LEARN[012]** — `@State` loops: state scoping, why `clearBlackboard = true` exists, why loop data must ride the state object (verified-doc semantics, cited).
- **Acceptance:** tests green; `grep -c "@State" src/main/kotlin/com/renovator/agent/states/Stages.kt` ≥ 1 on the interface; no `inner class` states (doc-verified constraint).
- **Demonstration:** `./mvnw -q test -Dtest='StateLoopTest,StateScopingTest,StateCarriedDataTest' && echo STATES-OK` → `STATES-OK`.

#### Task 4.2 — Failure → diagnosis → patch → verify loop on `fixture-api-removal`
- **Files:** `eval/canned/fixture-api-removal/diagnose.json`, `propose_patch.json` (canned LLM outputs: diagnosis naming `escapeSql`; patch replacing the call with a local escape implementation); no production-code changes expected — loop wiring from 4.1 + palette from Phase 3.
- **Tests:** `RepairLoopIT` (`docker-it`, mock LLM): `.agent diagnoses the escapeSql removal, patches, and reaches green`; `.trajectory shows Verifying then Repairing then Applying in that order`; `.exactly one repair cycle was needed`; `RepairLoopIT.patch passed through validatePatch before touching the executor` (trajectory contains `ValidationOutcome` for the patch before `BuildObserved`).
- **Acceptance:** IT green; the failing build's `Excerpt` in the trajectory names `escapeSql`.
- **Demonstration:** `./mvnw -q -Pdocker-it test -Dtest=RepairLoopIT && grep -c escapeSql var/runs/*/trajectory.jsonl` → count ≥ 2 (diagnosis + patch justification).

#### Task 4.3 — Two-hop replanning demo on `fixture-transitive-conflict` (§6.1 trace)
- **Files:** `eval/canned/fixture-transitive-conflict/*.json` (canned: direct plan; diagnosis with `PIN_TRANSITIVE`/`MULTI_HOP` hints; two-hop replan with `MANAGEMENT` then `DIRECT` steps), `scripts/demo-replan.sh` (runs the fixture, extracts `PlanAttempted` events from the trajectory, prints the trace), `docs/demo-replan.md` (generated from the real run, committed).
- **Tests:** `TwoHopReplanIT` (`docker-it`, mock LLM): `.direct bump fails enforcer convergence naming guava`; `.replan proposes management-scope pin then direct bump`; `.final build green`; `.trajectory matches the section 6.1 step sequence`. `DemoScriptTest.demo-replan.sh exits 0 and its output contains both plan attempts`.
- **Acceptance:** IT green; `docs/demo-replan.md` contains the real trajectory lines (regenerated, not hand-written — gate checks the file's embedded run id exists under `var/runs/`).
- **Demonstration:** `scripts/demo-replan.sh` → prints attempt 1 (direct, failed: dependencyConvergence) then attempt 2 (pin + bump, green).

#### Task 4.4 — Honest termination on `fixture-no-path` (bounded planner, C-7)
- **Files:** `src/main/kotlin/com/renovator/config/ProcessOptionsFactory.kt` (builds `ProcessOptions` with `control = firstOf(maxActions(renovator.budget.max-actions /* default 25 */), ON_STUCK)`), `src/main/kotlin/com/renovator/agent/actions/RequestHumanDecisionAction.kt` (produces `UpgradeBlocker` from the attempt history, then parks via `WaitFor.formSubmission` — C-3/C-6), `eval/canned/fixture-no-path/propose_plan.json` (canned plans all targeting the nonexistent version).
- **Tests:** `TerminationIT` (`docker-it` not required — L3 rejection precedes any build; mock LLM): `.agent exhausts plan space and terminates in Blocked with an UpgradeBlocker`; `.blocker lists every attempt with its L3 rejection reason`; `.process never exceeds maxActions actions` (history size assertion); `.stage Applying never appears in the trajectory`. `BudgetEnforcementIT.lowering maxActions to 3 still terminates cleanly with a typed blocker`.
- **LEARN:** **LEARN[014]** — honest termination: the attempt budget is a *verified framework mechanism* (cite `EarlyTerminationPolicy`), not a convention; why `fixture-no-path` is the fixture that proves recovery terminates instead of flailing.
- **Acceptance:** both ITs green; `mustNotVisitStages: [Applying]` asserted from the trajectory, matching `expected-outcome.yml`.
- **Demonstration:** `./mvnw -q test -Dtest='TerminationIT,BudgetEnforcementIT' && grep UpgradeBlocker var/runs/*/trajectory.jsonl | tail -1` → blocker JSON with attempts listed.

#### Task 4.5 — Kill-and-resume mid-upgrade (D10, C-5)
- **Files:** `src/main/kotlin/com/renovator/persistence/JsonFileAgentProcessRepository.kt` (extends `AbstractAgentProcessRepository`; Jackson snapshot to `var/runs/{id}/process.json` on every save/update; restores typed blackboard domain objects by registered type), `src/main/kotlin/com/renovator/persistence/RunSnapshot.kt` (our own snapshot: stage + domain objects + attempts — the pre-declared fallback path if live `AgentProcess` round-trip fails, KL-08 marker added now), `src/main/kotlin/com/renovator/api/RunService.kt` skeleton (`resume(runId)`: repository lookup → continue process / re-seed via `ProcessOptions.blackboard`), `scripts/demo-kill-resume.sh` (start service, submit run against `fixture-api-removal` with the mock provider, poll trajectory until stage `Applying`, `kill -9` the JVM, restart with `--resume <runId>`, assert completion).
- **Tests:** `JsonFileAgentProcessRepositoryTest.saved process state restores typed blackboard objects`; `JsonFileAgentProcessRepositoryTest.ephemeral processes are never persisted` (verifies the SPI contract, C-5); `KillResumeIT` (`docker-it`): `.resume continues a run interrupted during Applying and reaches UpgradeComplete`; `.trajectory shows a Resume marker and no repeated Analyze stage`.
- **LEARN:** **LEARN[013]** — Embabel process persistence (repository SPI over typed blackboard snapshots) vs Sentinel's Postgres checkpointer: what a JVM process snapshot buys, what it costs (types must round-trip; KL-08 honesty about the re-seed path).
- **Acceptance:** IT green; the demo script works against the real service process (output pasted into report).
- **Demonstration:** `scripts/demo-kill-resume.sh` → prints `KILLED at stage Applying (pid …)` then `RESUMED run … → UpgradeComplete`.

**Phase gate 4**
1. `./mvnw verify` AND `./mvnw -q -Pdocker-it verify` green (RepairLoopIT, TwoHopReplanIT, TerminationIT, BudgetEnforcementIT, KillResumeIT all included).
2. Protocol check; LEARN audit (LEARN[012]–LEARN[014]); KL-08/KL-09 current state recorded.
3. Git gate: clean tree; `git tag -a phase-4-complete`.
4. Phase report: the §6.1 trace realized — quote the two `PlanAttempted` trajectory lines; kill-resume transcript.

---

### Phase 5 — API, SSE & HITL

**Goal:** a reviewer can submit a goal, watch the planner think (replay-then-tail), and hold the approval gates.
**Star skill:** supporting cast — the loop made observable and interruptible (D11, D12).

#### Task 5.1 — REST control API
- **Files:** `src/main/kotlin/com/renovator/api/RunController.kt` (`POST /api/runs` `{repoPath, goal}` → `202 + runId`; `GET /api/runs/{id}` → status/stage/attempts; `GET /api/runs/{id}/trajectory?type=…`), `src/main/kotlin/com/renovator/api/dto/SubmitRunRequest.kt` (+ validation: repoPath must exist, be a directory under an allowed root, contain `pom.xml` — KL-03), `RunService` fleshed out (async run on a bounded executor; single-run enforcement, KL-01).
- **Tests:** `RunControllerTest.submitting a valid goal returns 202 and a run id` (`@WebMvcTest`); `.rejects a repo path outside allowed roots with 422`; `.rejects a non-Maven target with 422`; `.second concurrent submission returns 409`; `RunServiceIT.run transitions to done and exposes final stage`.
- **Acceptance:** tests green; error responses are typed (`ApiError(code, message)`), not stack traces.
- **Demonstration:** `./mvnw -q test -Dtest='RunControllerTest,RunServiceIT' && echo API-OK` → `API-OK`.

#### Task 5.2 — SSE progress stream, replay-then-tail (D12)
- **Files:** `src/main/kotlin/com/renovator/api/SseController.kt` (`GET /api/runs/{id}/stream` → `SseEmitter`; replays `TrajectoryStore` events with their sequence numbers, then tails live events from the application event bus; heartbeats every 15 s; completes on terminal event).
- **Tests:** `SseReplayIT.replayed events precede tailed live events with monotonically increasing sequence numbers`; `SseReplayIT.stream completes on the terminal event`; `SseReplayIT.subscribing to a finished run replays and closes immediately`.
- **LEARN:** **LEARN[015]** (part 1) — replay-then-tail semantics, same as Sentinel's stream: late subscribers see the whole story, not just the future.
- **Acceptance:** ITs green.
- **Demonstration:** `curl -N localhost:8080/api/runs/<id>/stream | head -30` (against a running fixture-clean upgrade) → replayed `StageEntered(Analyzing)` first, live events following.

#### Task 5.3 — HITL approval gates via `WaitFor` (D11, C-3/C-6)
- **Verification step (mandatory, first):** confirm the programmatic submission path for a parked `WaitFor` form in Embabel 1.5.1 (Context7 → `com.embabel.agent.core.hitl` sources → embabel-agent-examples). If none exists: implement the C-6 fallback (blackboard-poll gate action, `canRerun = true`, precondition = `HumanDecision` present; REST places the decision object). Record the outcome in the phase report; if the fallback is used, KL-09 flips to permanent.
- **Files:** `src/main/kotlin/com/renovator/agent/actions/ApprovalGateAction.kt` (armed by `renovator.approvals.*`; plan-approval and commit-candidate gates), `src/main/kotlin/com/renovator/api/DecisionController.kt` (`GET /api/runs/{id}/pending-decision` → rendered blocker/gate payload; `POST /api/runs/{id}/decisions` `{approved, comment}`).
- **Tests:** `ApprovalGateIT.process parks at the commit-candidate gate until approved, then finalizes`; `ApprovalGateIT.rejection routes to Repairing with the human comment on the blackboard`; `ApprovalGateIT.gate disarmed by config means no park`; `DecisionControllerTest.pending decision renders the blocker payload with attempts`.
- **LEARN:** **LEARN[015]** (part 2) — `WaitFor` ≈ a BPMN human task: the process parks, the outside world answers, the blackboard resumes it; how our REST layer stands in for the shell's form renderer.
- **Acceptance:** ITs green under whichever mechanism the verification step confirmed; the mechanism used is written down.
- **Demonstration:** `scripts/renovator watch <id>` shows `WAITING: approve commit candidate?`; `scripts/renovator decide <id> --approve` → run completes.

#### Task 5.4 — Minimal CLI
- **Files:** `scripts/renovator` (bash + curl + python3 for JSON: `submit`, `watch` (SSE via `curl -N`), `decide`, `trajectory`, `status`).
- **Tests:** `CliSmokeIT.submit watch decide happy path against a running service` (spins the app on a random port, `docker-it` profile).
- **Acceptance:** CLI drives the full flow without IntelliJ or a browser.
- **Demonstration:** the CLI session transcript in the phase report.

**Phase gate 5**
1. `./mvnw verify` AND `./mvnw -q -Pdocker-it verify` green.
2. Protocol check; LEARN audit (LEARN[015] both parts).
3. Git gate: clean tree; `git tag -a phase-5-complete`.
4. Phase report: WaitFor verification outcome (C-6) stated with evidence; CLI transcript.

---

### Phase 6 — Evals & observability

**Goal:** the fixture outcomes run as an eval suite (mock = gate, live = measured); the audit trail and metrics are queryable.
**Star skill:** the deterministic judge, now as a regression harness (D13, D14).

#### Task 6.1 — Eval harness, mock mode (100% threshold)
- **Files:** `src/main/kotlin/com/renovator/eval/EvalRunner.kt` (iterates `fixtures/*/expected-outcome.yml`; runs each with the fixture's canned LLM responses from `eval/canned/`; compares terminal state, required/forbidden stages, attempt ceiling; writes `eval/reports/<date>-mock.md`), Maven profile `eval-mock` in `pom.xml`.
- **Tests:** `MockEvalIT.all four fixtures meet their expected outcomes` (fails the build at anything below 4/4); `EvalRunnerTest.mismatched terminal state fails the fixture`; `EvalRunnerTest.exceeding maxAttempts fails the fixture`.
- **Acceptance:** mock eval 100% — this is a hard CI gate.
- **Demonstration:** `./mvnw -q -Peval-mock,docker-it verify && tail -5 eval/reports/*-mock.md` → `4/4 fixtures as expected`.

#### Task 6.2 — Eval harness, live mode (stated floor)
- **Files:** Maven profile `eval-live`; `eval/reports/<date>-live.md` (per-fixture outcome, provider, model, durations).
- **Tests:** `LiveEvalIT` (`llm-it`; never in the default build).
- **Acceptance (D13 floor):** `fixture-clean` **and** `fixture-no-path` must pass (≥ 50% and those two specifically — clean upgrade competence + honest termination are the non-negotiable behaviors); `fixture-api-removal` and `fixture-transitive-conflict` are reported. Threshold applies to whichever provider is configured (KL-06).
- **Demonstration:** `LLM_PROVIDER=cloud ./mvnw -q -Peval-live,docker-it verify; tail -8 eval/reports/*-live.md` (env note if run with Ollama instead).

#### Task 6.3 — Micrometer metrics
- **Files:** `src/main/kotlin/com/renovator/observability/RenovatorMetrics.kt` (counters `renovator.plans.attempted`, `renovator.replans.total`, `renovator.validation.rejections{check=…}`, `renovator.escalations.total`; timer `renovator.time.to.green`), wired into actions via a small interceptor; `application.yml` exposes `/actuator/prometheus`.
- **Tests:** `MetricsIT.after a fixture-clean run, plans-attempted is 1 and time-to-green is recorded`; `MetricsIT.after a no-path run, validation rejections carry the L3 check tag and escalations is 1`; `MetricsIT.prometheus endpoint exposes all four meter names`.
- **Acceptance:** ITs green.
- **Demonstration:** `curl -s localhost:8080/actuator/prometheus | grep renovator_` after a run → the four meters with values.

#### Task 6.4 — Trajectory query + audit-trail doc
- **Files:** `GET /api/runs/{id}/trajectory?type=ValidationRejection&stage=…` (query params on the existing endpoint), `docs/audit-trail.md` ("show me every decision the agent made" — worked example over a real run).
- **Tests:** `TrajectoryQueryTest.filters by event type`; `TrajectoryQueryTest.filters by stage`; `TrajectoryQueryTest.every trajectory line is valid JSON with a sequence number` (property-flavored: runs over all files under `var/runs/` produced by the test suite).
- **LEARN:** **LEARN[016]** — the audit trail is a feature: why every proposal, rejection, and plan event is persisted before any UI exists.
- **Acceptance:** tests green; doc committed.
- **Demonstration:** `scripts/renovator trajectory <id> --type ValidationRejection` on a `fixture-no-path` run → one line per attempt, each with check name + reason.

**Phase gate 6**
1. `./mvnw verify` AND `./mvnw -q -Peval-mock,docker-it verify` (100%) green; live-eval report committed (or KL-06 env note).
2. Protocol check; LEARN audit (LEARN[016]).
3. Git gate: clean tree; `git tag -a phase-6-complete`.
4. Phase report: eval tables (mock + live), metrics scrape output.

---

### Phase 7 — Docs & final audit

**Goal:** the README opens with accessibility, not architecture (Appendix A ordering is verbatim-mandatory); protocols hold under audit; a stranger can reproduce from a clean clone.
**Star skill:** the bounded claim, stated precisely.

#### Task 7.1 — README per the mandated structure
- **Files:** `README.md` — order enforced: (1) "What is this, in plain language"; (2) "Where this kind of system applies — and where it doesn't" with the §8-of-brief material **verbatim** (Appendix A of this plan carries it); (3) the bounded-claim closing; **only then** architecture (§4 diagram), quickstart, demo walkthroughs (replan, kill-resume), design decisions (D-table summary), known limitations (one sentence per user-visible KL entry).
- **Tests:** `ReadmeStructureTest.what-is-this heading precedes any architecture heading`; `ReadmeStructureTest.the where-it-applies items appear verbatim` (grep -F on the six item titles and the closing framing sentence); `ReadmeStructureTest.every user-visible KNOWN_LIMITATIONS entry has a README sentence` (mirrors checker rule 3).
- **Acceptance:** tests green; a non-engineer reader finishes the first screen knowing what Renovator does and where it must not be used (owner judgment call at review — recorded in the report).
- **Demonstration:** `./mvnw -q test -Dtest=ReadmeStructureTest && echo README-OK` → `README-OK`.

#### Task 7.2 — Full protocol audit
- **Files:** none new; audit outputs committed under `docs/phase-reports/final-audit.md`.
- **Actions:** `python3 scripts/check_protocols.py --full` (all rules, all files, all history boundary states); LEARN index completeness cross-check (every number 001–016 present or struck, exactly one location each); KNOWN_LIMITATIONS 1:1 both directions; verify all `phase-N-complete` tags exist and each tag message carries gate evidence.
- **Acceptance:** audit output shows `0 violations`; tag listing pasted.
- **Demonstration:** `python3 scripts/check_protocols.py --full && git tag -l 'phase-*-complete'` → `0 violations` + 8 tags.

#### Task 7.3 — Clean-clone reproduction
- **Actions:** `git clone file://$PWD /tmp/renovator-clone && cd /tmp/renovator-clone && ./mvnw -q verify && ./mvnw -q -Peval-mock,docker-it verify && scripts/demo-replan.sh && scripts/demo-kill-resume.sh` — all on the WSL2-native fs (D15), all green; record the environment (OS, Java, Docker, provider) in the report.
- **Acceptance:** the clone, with no local state and no uncommitted files, passes every gate a fresh contributor would hit.
- **Demonstration:** the clone transcript in `final-audit.md`.

#### Task 7.4 — Final phase report + trilogy framing check
- **Files:** `docs/phase-reports/phase-7.md` (full Appendix B format; includes a paragraph tracing the bounded claim — deterministic judge + cheap reversibility — to: the executor boundary (§4.2), the sandbox (D7), the judge fixtures (§8), and the "where it doesn't" README section).
- **Acceptance:** report exists; every prior phase report is complete per Appendix B; README closing paragraph present verbatim-intent.
- **Demonstration:** `ls docs/phase-reports/ | wc -l` → `9` (phase-0..7 + final-audit).

**Phase gate 7 (final)**
1. All of 7.1–7.4 acceptance criteria green; `./mvnw verify` + `-Peval-mock,docker-it verify` green in the *clone*.
2. Protocol check `--full`; LEARN audit complete (16 essays).
3. Git gate: clean tree (both repo and clone discarded after); `git tag -a phase-7-complete`.
4. Phase 7 report + final-audit committed.

---

## Appendix A — README mandated opening (verbatim source of truth for Task 7.1)

The README **opens with accessibility, not architecture.** A person who has never programmed but knows computers must finish the first screen with a precise picture of what Renovator does, where it applies, and where it must not be used. Required opening structure, in this exact order, **before any technical content**:

**A.1 — "What is this, in plain language."** 3–4 sentences, zero jargon. Convey: (a) it's an assistant that upgrades old software to new versions; (b) it cannot touch code directly — it only *suggests* changes, and an automatic checker decides whether each suggestion is safe before anything happens; (c) if an upgrade breaks something, it reads the failure and tries a different way; (d) if it runs out of ideas, it asks a human instead of guessing.

**A.2 — "Where this kind of system applies — and where it doesn't."** The following material **verbatim** (each item may gain one plain-English framing sentence for non-engineers; the substance below is not to be rewritten):

*Where it applies*

1. **Framework/language migrations (beyond dependency bumps).** Spring Boot 2 → 3 with the `javax.*` → `jakarta.*` package rename, JUnit 4 → 5, Java 17 → 25 with removed APIs. The LLM proposes typed `CodePatch`es; the compiler and test suite are the validator. Perfect fit because the failure signal is precise — a compile error *names* the broken API, which feeds the planner's next attempt. This is literally Renovator scaled up, and it's a real enterprise pain point (large shops have hundreds of services stuck on old Spring versions).
2. **Database schema migration planning.** Goal: `TargetSchemaLive` without breaking the app. LLM proposes a `MigrationPlan` (expand-contract steps: add column → backfill → switch reads → drop old column). Validators: SQL parses, migration is reversible, no locking operations on hot tables, every app query still resolves against the intermediate schemas. Error recovery: a migration step fails in staging → planner sees which step and why → replans the remaining path (smaller batches, different index strategy). Flyway/Liquibase gives you the deterministic executor; the planner supplies what they lack — judgment when the scripted path fails.
3. **IaC / configuration remediation.** "Terraform plan must be clean and policy-compliant." LLM proposes `ConfigChange`s to Kubernetes manifests, Terraform modules, or security group rules. Validators are off-the-shelf deterministic tools: `terraform validate`, OPA/Conftest policies, kube-score, YAML schema. A rejected plan comes back with the exact policy violation as the observation — planner reroutes around it. Real-world analog already exists in products (policy-as-code pipelines); the agent adds the replanning loop instead of just failing the pipeline.

*Where it doesn't*

1. **Anything with irreversible real-world side effects and no sandbox.** Payment execution, production database drops, sending customer-facing emails, trading. The pattern's error recovery assumes *failure is survivable information*. "The planner will notice it went wrong and try something else" is a nightmare sentence when the first attempt wired money. You can bolt human approval onto every step — but then you've built a form wizard, not a dynamic planner, and Embabel's replanning is wasted.
2. **Domains with no deterministic judge.** "Write a marketing campaign," "summarize this legal contract," "improve this UI's UX." There's no validator you can write — the only arbiter of quality is another LLM or a human, which reintroduces the non-determinism you were supposed to be fencing off. You can still use Embabel here (it orchestrates fine), but the *guardrails story* collapses: your validator is a vibe check, and a reviewer will poke at exactly that. This is why the judge being deterministic is the load-bearing requirement.
3. **Hard real-time / latency-critical paths.** Rate limiting decisions, HFT, network packet processing, anything with a millisecond budget. A Thought→Action→Observation loop with LLM latency (hundreds of ms to seconds *per cycle*, multiple cycles per plan, plus replanning) is three orders of magnitude too slow. The owner's first project is the perfect counterexample: the `/v1/check` hot path targets ~1–2 ms — an agent deciding per request would be absurd there. Agents belong at the *control plane* (deciding what the rules should be), never the *data plane* (enforcing them per request).

**A.3 — Closing framing sentence (verbatim intent, wording may be polished):** the items in "Where it doesn't" are not agent failures — they are cases where one of the two load-bearing properties (**a deterministic judge** and **cheap reversibility**) or the latency budget is absent. The project's claim is deliberately bounded: *"we know exactly which property makes this architecture safe, and we can name the classes of problems where it isn't."* Bounded claims read more credibly than "agents can automate anything."

Only after these sections may the README proceed to architecture, quickstart, demo walkthroughs, design decisions, and known limitations.

---

## Appendix B — Phase report template (`docs/phase-reports/phase-N.md`)

```markdown
# Phase N report — <phase title>

- Date: <date>; Executor: dsh Standard; Branch state at gate: <short-sha>
- Environment: <OS/Java/Docker/provider actually used>; Environment notes (drift absorbed per §13.3): <none | list>

## Gate evidence
| Gate command | Result | dsh trajectory checkpoint |
|---|---|---|
| ./mvnw verify | BUILD SUCCESS | <checkpoint ref> |
| …(phase-specific commands)… | … | … |
| python3 scripts/check_protocols.py --phase-boundary | 0 violations | <checkpoint ref> |
| git status --porcelain | (empty) | <checkpoint ref> |

## Demonstration outputs
<For every task Demonstration line: the command and its observed output, verbatim.>

## Commits
| Commit | Message |
|---|---|
| <sha> | phase-N.M: <task title> |
Tag: phase-N-complete (annotated; message carries gate summary)
Hook attestation: pre-commit ran on every commit above (one hook output line per commit pasted below or referenced).

## LEARN audit
New LEARN comments this phase: [NNN, …] — index updated in commit(s) <sha>.
### Quoted LEARN (one, in full)
> <the full comment block>
**Restate-test self-assessment:** <what this teaches that the code cannot show; why ≥6 lines is justified or why it is a labeled stub>

## Deviations & limitations
<KN entries opened/closed; any fallback taken (§2 matrix); none otherwise.>
```

---

## Appendix C — Protocol checker spec (`scripts/check_protocols.py`)

Stdlib-only Python 3. Exit 0 = clean; exit 1 with one line per violation. Rules:

1. **LEARN format.** Regex `LEARN\[(\d{3})\]` over `src/**`, `fixtures/**`, `scripts/**`, `docs/**` (exclude `LEARN_INDEX.md`, phase reports). Each block must contain the fields `Why this way:`, `Good sides:`, `Drawbacks:`, `Concept:` (plus optional `See also:`), and span ≥ 6 lines unless it matches `LEARN-REF\[(\d{3})\]` (labeled stub: exempt from fields and floor, must reference an existing number).
2. **Numbering & index.** Numbers gapless from 001 (no gaps, no duplicates, no reuse); `LEARN_INDEX.md` contains exactly one row per number with matching title and current `file:line` (checker recomputes line numbers).
3. **Deferred work 1:1.** Every `TODO(review)` marker carries an id (`TODO(review) KL-NN`); every `KL-NN` row in `KNOWN_LIMITATIONS.md` maps to exactly one live marker or is struck-through with a closing commit reference; counts match both directions; rows marked `user-visible: yes` have a matching sentence in `README.md` (checked from Phase 7 on; advisory before).
4. **Prompt location.** From Phase 3 on (flag `--phase>=3` or read from git tag presence): prompt-shaped multi-line literals (heuristic: triple-quoted strings ≥ 3 lines containing instruction verbs list) outside `src/main/resources/prompts/` fail the build; before Phase 3, warn.
5. **Phase boundary.** With `--phase-boundary`: `git status --porcelain` must be empty; the newest `phase-*-complete` tag must be reachable.
6. **Self-test.** `scripts/test_check_protocols.py` covers each rule positive+negative (Task 0.6 names the methods).

---

*End of PLAN.md. Authored 2026-08-30; D1–D15 fixed; all Embabel capabilities verified against current docs and source per §2; versions pinned per §3 with the re-verification rule binding at execution time.*
