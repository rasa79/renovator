# Renovator

**What this is, in plain language.** An agent that upgrades a Java/Maven project's dependencies — and, when the upgrade breaks the build, *fixes it itself*: it reads the compile/conflict error, proposes a patch or a better plan, re-runs, and repeats until the build is green or it honestly stops and asks a person. It does all of that inside a throwaway Docker container per attempt, so your real project is never touched, and it records every decision it made in a file you can read afterward.

**Where this kind of system applies — and where it doesn't.**

This is a system for *automated dependency upgrades of plain Maven projects* — a well-defined chore with a **deterministic judge** (the build itself) and **cheap reversibility** (a throwaway copy + container per attempt). That combination is what makes an "agent" here safe: it can be wrong cheaply, and a wrong attempt can never corrupt the thing you care about. It does **not** apply to problems without such a judge — free-form code generation, design, or anything where "correct" is a judgment the agent must make on its own. The safety claim runs on the judge and reversibility, never on the model's confidence.

## Setup (clone-and-reproduce)

Requirements:

- **Java 25** (Eclipse Temurin) — the toolchain.
- **Docker** with the WSL2 / engine integration — the sandbox runs each build in `maven:3.9.11-eclipse-temurin-25`.
- **An LLM provider** — either an **OpenAI-compatible API key** (`OPENAI_API_KEY`) or a local **Ollama** (`LLM_PROVIDER=ollama`). Model defaults to `gpt-4.1-mini` (the cheapest adequate one).

```bash
# build + run the unit suite (no Docker)
./mvnw verify

# run the Docker integration suite (the sandbox builds)
./mvnw -Pdocker-it verify
```

Optional env knobs: `LLM_PROVIDER` (`cloud`|`ollama`), `LLM_MODEL`, `LLM_BASE_URL`, `LLM_API_KEY`, `RENOVATOR_ALLOWED_ROOTS` (allowed repo roots for submissions; default = the working directory).

## The control API + CLI

Start the service:

```bash
./mvnw spring-boot:run
```

Then drive it with the CLI (`scripts/renovator`):

```bash
export RENOVATOR_URL=http://localhost:8080
renovator submit fixtures/fixture-clean org.apache.commons:commons-lang3:3.12.0:3.14.0   # -> a run id
renovator status <runId>                # status / stage / attempts
renovator trajectory <runId>            # the full audit trail
renovator trajectory <runId> --type ValidationRejection   # filter by event type
renovator watch <runId>                 # SSE replay-then-tail stream
renovator decide <runId> approve|reject "comment"   # answer an approval gate
```

Endpoints: `POST /api/runs`, `GET /api/runs/{id}`, `GET /api/runs/{id}/trajectory?type=…&stage=…`, `GET /api/runs/{id}/stream` (SSE), `GET /api/runs/{id}/pending-decision`, `POST /api/runs/{id}/decisions`, `GET /actuator/prometheus` (metrics).

Note: the host must be able to reach the repo path you submit — `repoPath` must be a directory under an allowed root and contain a `pom.xml` (Maven-only, `KL-03`).

## Demo walkthroughs

**Two-hop replanning** (a direct bump fails enforcer `dependencyConvergence`; the agent diagnoses, then pins the transitive + bumps direct):

```bash
bash scripts/demo-replan.sh
```

**Kill-and-resume** (the run JVM is `kill -9`ed mid-flight; a fresh JVM resumes from the persisted snapshot to completion):

```bash
bash scripts/demo-kill-resume.sh
```

## The eval

The four fixtures in `fixtures/` are the eval dataset (each with an `expected-outcome.yml`).

```bash
# mock mode — the HARD gate: 4/4 fixtures with canned LLM responses, fails below 4/4
./mvnw -q -Peval-mock,docker-it verify && tail -5 eval/reports/*-mock.md

# live mode — measure against the configured provider (opt-in; spends real tokens)
LLM_SMOKE=1 ./mvnw -q -Peval-live,docker-it verify; tail -8 eval/reports/*-live.md
```

Per-fixture verdicts, attempts, terminal state, and (live) durations are written to `eval/reports/<date>-mock.md` / `-live.md`. The mock gate is the CI signal; the live run is a *measurement* (the live model may propose a plan the fixture cannot apply — recorded honestly, never asserted on model confidence).

## Design, in one paragraph

A GOAP planner picks actions from a palette; the LLM only *proposes* typed plans/diagnoses/patches. Every proposal crosses a validation pipeline (path whitelist → diff-applies → domain invariants → optional dry-run compile) whose accepted output is a `Validated*` wrapper with a sha256 proof — the executor takes **only** those. The judge (the sandbox build) is deterministic; a red build loops back to the repair lane (patch or replan by the diagnosis's hint), a plan-space ceiling escalates honestly to a human, and every decision is appended to a typed trajectory.

## Known limitations

- Single process, one run at a time (a second concurrent submit is `409`).
- No auth on the control API (sandbox containers are the security boundary; demo posture).
- Maven projects only (`KL-03`): no Gradle, no non-Maven targets, no multi-module reactors.
- LLM diagnoses are advisory — correctness is asserted by the deterministic build/test outcomes, never by the model.
- The eval is a 4-fixture smoke signal, not a benchmark.
- A local Ollama path may be slow on modest hardware.
- The resume re-enters at the last apply (in-flight state is re-derived); runs before the first apply are not resumable.
- Binary / rename / deletion diffs are rejected by scope (Layer 2).
- There is no programmatic WaitFor submission in the framework release used; the approval gate is resolved by the documented C-6 re-seed pattern (`KL-09`, permanent).
