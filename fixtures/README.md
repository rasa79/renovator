# The fixtures — the deterministic judge

Four small Maven projects that exist to give Renovator's agent something
*known-answer* to be judged against. They are deliberately **not** modules of the
Renovator build (D4) and are **never built in place by the runtime** — the sandbox
runner always copies the workspace first (D7). The four `expected-outcome.yml`
files are the eval dataset (D13).

```text
// LEARN[003] Judge before judged: why the fixtures land before the agent code
// Why this way: an agent that claims to do safe upgrades is unproven until something
//   can say "wrong" with certainty. The fixtures are that something — deterministic,
//   hand-built breakage whose failure modes are known in advance (a library API that
//   vanished, an enforcer rule that fires, a version that does not exist). Building
//   them before writing any agent code means the judge's verdicts are fixed first and
//   the agent has to earn them; building them after would let agent convenience leak
//   into the definition of "correct".
// Good sides: each fixture doubles as a regression case (mock eval = 100% gate) and as
//   documentation of what breakage looks like; the expected outcomes read like a spec
//   for the planner, because that is what they are.
// Drawbacks: these are toy repos — real breakage is messier (multi-module, config
//   plugins, platform-specific). The judge is honest only within the fixture scope
//   (KL-03: Maven-only, single-module), and nothing here proves the agent generalizes.
// Concept: think of tests vs. the code under test, but one level up: fixtures are the
//   test *oracle* for an entire agent program. Their fidelity bounds the program's
//   credibility — hence the priority order in PLAN §14.
// See also: PLAN §8, PLAN D13, docs/verification-log.md
```

## Catalog

| Fixture | Breakage injected | Decision the judge makes | Expected terminal state |
|---|---|---|---|
| `fixture-clean` | none — a `commons-lang3` 3.12.0 → 3.14.0 bump with a used-API that is unchanged | accepts the correct upgrade | `UpgradeComplete` |
| `fixture-api-removal` | `commons-lang:commons-lang:2.6` → `commons-lang3` 3.14.0: the used API (`StringEscapeUtils`) was removed (javac fails naming the type) | rejects the naive coordinate swap; a patched code change passes | `UpgradeComplete` (one repair loop) |
| `fixture-transitive-conflict` | `guava` direct bump without handling guice's fixed transitive pin → `dependencyConvergence` fails naming guava | rejects the single-hop plan; accepts the two-hop (pin + bump) | `UpgradeComplete` (one replan) |
| `fixture-no-path` | target version `99.99.99` does not exist (404 by construction) | rejects every plan at the existence check; the loop must terminate honestly | `UpgradeBlocker` + human escalation |

## How breakage is injected

Everything is a **library-version fact**, not a test double: the fixtures use real
Central artifacts and real Maven mechanics (javac symbol resolution, the enforcer's
`dependencyConvergence`, HTTP 404 for missing versions), and the judge's verdicts
are plain `mvn` exit codes plus typed build results. See each fixture's README for
the exact mechanism and reproduction commands.

## Baseline sanity (run by `FixtureSanityTest` / `./mvnw verify`)

Each fixture's baseline build is green on its own; the breakage is produced by the
goal or by the scripted coordinate swap in the authoring-time tests — the runtime
never mutates a fixture (asserted by `DockerSandboxRunnerIT.never mutates the
source fixture directory`).
