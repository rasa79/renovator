# Renovator

## What is this, in plain language

Renovator is an assistant that upgrades old software to new versions. It cannot touch
your code directly — it only *suggests* changes, and an automatic checker decides whether
each suggestion is safe before anything happens. If an upgrade breaks something, it reads
the failure and tries a different way. If it runs out of ideas, it asks a human instead of
guessing.

## Where this kind of system applies — and where it doesn't

*Where it applies*

1. **Framework/language migrations (beyond dependency bumps).** Spring Boot 2 → 3 with the `javax.*` → `jakarta.*` package rename, JUnit 4 → 5, Java 17 → 25 with removed APIs. The LLM proposes typed `CodePatch`es; the compiler and test suite are the validator. Perfect fit because the failure signal is precise — a compile error *names* the broken API, which feeds the planner's next attempt. This is literally Renovator scaled up, and it's a real enterprise pain point (large shops have hundreds of services stuck on old Spring versions).
2. **Database schema migration planning.** Goal: `TargetSchemaLive` without breaking the app. LLM proposes a `MigrationPlan` (expand-contract steps: add column → backfill → switch reads → drop old column). Validators: SQL parses, migration is reversible, no locking operations on hot tables, every app query still resolves against the intermediate schemas. Error recovery: a migration step fails in staging → planner sees which step and why → replans the remaining path (smaller batches, different index strategy). Flyway/Liquibase gives you the deterministic executor; the planner supplies what they lack — judgment when the scripted path fails.
3. **IaC / configuration remediation.** "Terraform plan must be clean and policy-compliant." LLM proposes `ConfigChange`s to Kubernetes manifests, Terraform modules, or security group rules. Validators are off-the-shelf deterministic tools: `terraform validate`, OPA/Conftest policies, kube-score, YAML schema. A rejected plan comes back with the exact policy violation as the observation — planner reroutes around it. Real-world analog already exists in products (policy-as-code pipelines); the agent adds the replanning loop instead of just failing the pipeline.

*Where it doesn't*

1. **Anything with irreversible real-world side effects and no sandbox.** Payment execution, production database drops, sending customer-facing emails, trading. The pattern's error recovery assumes *failure is survivable information*. "The planner will notice it went wrong and try something else" is a nightmare sentence when the first attempt wired money. You can bolt human approval onto every step — but then you've built a form wizard, not a dynamic planner, and Embabel's replanning is wasted.
2. **Domains with no deterministic judge.** "Write a marketing campaign," "summarize this legal contract," "improve this UI's UX." There's no validator you can write — the only arbiter of quality is another LLM or a human, which reintroduces the non-determinism you were supposed to be fencing off. You can still use Embabel here (it orchestrates fine), but the *guardrails story* collapses: your validator is a vibe check, and a reviewer will poke at exactly that. This is why the judge being deterministic is the load-bearing requirement.
3. **Hard real-time / latency-critical paths.** Rate limiting decisions, HFT, network packet processing, anything with a millisecond budget. A Thought→Action→Observation loop with LLM latency (hundreds of ms to seconds *per cycle*, multiple cycles per plan, plus replanning) is three orders of magnitude too slow. The owner's first project is the perfect counterexample: the `/v1/check` hot path targets ~1–2 ms — an agent deciding per request would be absurd there. Agents belong at the *control plane* (deciding what the rules should be), never the *data plane* (enforcing them per request).

The items in "Where it doesn't" are not agent failures — they are cases where one of the two
load-bearing properties (**a deterministic judge** and **cheap reversibility**) or the
latency budget is absent. The project's claim is deliberately bounded: *"we know exactly which property makes this architecture safe, and we can name the classes of problems where it isn't."* Bounded claims read more credibly than "agents can automate anything."

---

## Architecture (brief)

A GOAP planner picks actions from a palette; the LLM only *proposes* typed
plans/diagnoses/patches. Every proposal crosses a validation pipeline (path whitelist →
diff-applies → domain invariants → optional dry-run compile) whose accepted output is a
`Validated*` wrapper carrying a sha256 proof — the executor takes **only** those. The judge
(the sandbox build) is deterministic; a red build loops back to the repair lane (patch or
replan per the diagnosis's hint), a plan-space ceiling escalates honestly to a human, and
every decision is appended to a typed trajectory (`docs/audit-trail.md`).

## Setup (clone-and-reproduce)

Requirements:

- **Java 25** (Eclipse Temurin) — the toolchain.
- **Docker** with the WSL2 / engine integration — the sandbox runs each build in `maven:3.9.11-eclipse-temurin-25`.
- **An LLM provider** — an **OpenAI-compatible API key** (`OPENAI_API_KEY`) or a local **Ollama** (`LLM_PROVIDER=ollama`). Runtime model pin: `LLM_MODEL` (default `gpt-4.1-mini`; the **live eval floor is pinned to `gpt-4.1`**).

```bash
./mvnw verify                                   # unit suite (no Docker)
./mvnw -Pdocker-it verify                       # Docker integration suite
```

Env knobs: `LLM_PROVIDER` (`cloud`|`ollama`), `LLM_MODEL`, `LLM_BASE_URL`, `LLM_API_KEY`,
`RENOVATOR_ALLOWED_ROOTS` (allowed repo roots for submissions; default = working dir).

## Quickstart (control API + CLI)

```bash
./mvnw spring-boot:run                          # start the service (port 8080)
export RENOVATOR_URL=http://localhost:8080
scripts/renovator submit fixtures/fixture-clean org.apache.commons:commons-lang3:3.12.0:3.14.0
scripts/renovator status <runId>
scripts/renovator trajectory <runId>            # the audit trail
scripts/renovator trajectory <runId> --type ValidationRejection
scripts/renovator watch <runId>                 # SSE replay-then-tail
scripts/renovator decide <runId> approve|reject "comment"   # answer an approval gate
```

## Demo walkthroughs

```bash
bash scripts/demo-replan.sh         # two-hop replanning (enforcer convergence) over a real run
bash scripts/demo-kill-resume.sh    # kill -9 the run JVM; a fresh JVM resumes to completion
```

## The eval

```bash
# mock mode — the HARD CI gate: 4/4 fixtures with canned LLM responses
./mvnw -q -Peval-mock,docker-it verify && tail -5 eval/reports/*-mock.md

# live mode — MEASURED against the configured provider (opt-in; spends real tokens)
LLM_SMOKE=1 LLM_MODEL=gpt-4.1 ./mvnw -q -Peval-live,docker-it verify; tail -8 eval/reports/*-live.md
```

Results land in `eval/reports/<date>-mock.md` / `-live.md`. The **live floor is pinned to
`gpt-4.1`** and asserts that `fixture-clean` and `fixture-no-path` pass. Expected-vs-actual:
`fixture-no-path`'s terminal is **`UpgradeBlocker`** (the honest plan-space escalation — that
*is* its expected outcome, a **PASS**), and `fixture-clean`'s is `UpgradeComplete`.

**Comparison (model × fixture × outcome):** the sequence that established the floor is
recorded in the phase-6 report — gpt-4.1-mini (ungrounded prompt) = baseline FAIL on both
floor fixtures; gpt-4.1 (ungrounded prompt) = fixture-clean FAIL; **gpt-4.1 (grounded
prompt) = fixture-clean PASS + fixture-no-path PASS**. gpt-4.1-mini's failure and the
ungrounded 4.1 run are retained untouched in `eval/reports/` as the honest baseline.

## Design decisions (D-table summary)

- **Judge before judged** — the fixtures/sandbox precede the agent; the build is the judge.
- **The executor takes only `Validated*`** with a recomputed sha256 proof; enforcement is at the boundary, not in prompts.
- **Sandbox reversibility** — a throwaway container + pristine copy per attempt; the source tree is never mutated.
- **GOAP planning only** (utility went STUCK in Task 0.3); costs are guidance, never correctness.
- **The attempt budget is a framework mechanism** (`firstOf(maxActions, ON_STUCK)`), with the agent's own report-before-cut ceiling.
- **The audit trail is a feature** — written first, typed, sequence-numbered.

## Known limitations

> One line per user-visible KL entry (see `KNOWN_LIMITATIONS.md` for the full ledger).

- Single process, one run at a time — a second concurrent submit is `409` (KL-01).
- No auth on the control API — demo posture; the sandbox containers are the security boundary (KL-02).
- Maven projects only — no Gradle / non-Maven / multi-module targets (KL-03).
- LLM diagnoses are advisory — correctness is asserted by the deterministic build/test outcomes, never by the model (KL-04).
- The eval is a 4-fixture smoke signal, not a benchmark (KL-05).
- A local Ollama path may be slow on modest hardware (KL-06).
- The resume re-enters at the last apply; runs before the first apply are not resumable (KL-08).
- There is no programmatic WaitFor submission in the framework release used; the approval gate is resolved by the documented re-seed pattern (KL-09).
- Binary / rename / deletion diffs are rejected by scope (KL-10).
- The live-model repair-path prompts still carry placeholder values; the non-floor fixtures' repair can fail typed binding under the live model (KL-13, live).
