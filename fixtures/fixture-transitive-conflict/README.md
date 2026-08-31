# fixture-transitive-conflict

The single-hop-fails fixture (PLAN §8.3): a direct version bump breaks Maven's
`dependencyConvergence` enforcer rule because a fixed transitive dependency pins
the artifact at an older version. Resolution requires a two-hop plan (pin via
`dependencyManagement`, then bump the direct dependency) — or upgrading the
transitive pinner consistently.

## Mechanism (verified 2026-08-30)

- Direct dependency: `com.google.guava:guava:31.0.1-jre` (upgrade target `33.4.8-jre`).
- **Fixed** dependency: `com.google.inject:guice:7.0.0` — transitively pins
  guava at **31.0.1-jre** (verified below). It must NOT be touched by the goal.
- `maven-enforcer-plugin:3.6.3` with `dependencyConvergence` bound to `validate`
  (uses artifact `org.apache.maven.enforcer:enforcer-rules:3.6.3` — note the
  artifact is `enforcer-rules`, not `maven-enforcer-rules`).

### `dependency:tree` evidence (baseline, verbose)

```
[INFO] +- com.google.guava:guava:jar:31.0.1-jre:compile
[INFO] +- com.google.inject:guice:jar:7.0.0:compile
[INFO] |  \- (com.google.guava:guava:jar:31.0.1-jre:compile - omitted for duplicate)
```

Both paths resolve guava **31.0.1-jre** — converged, baseline `validate` is green.

### Direct bump → deterministic enforcer failure (names guava; run twice, identical)

```
[ERROR] Dependency convergence error for com.google.guava:guava:jar:33.4.8-jre. Paths to dependency are:
[ERROR]   +-com.google.guava:guava:jar:33.4.8-jre:compile
[ERROR]     +-com.google.guava:guava:jar:31.0.1-jre:compile
```

## Coordinate drift note (PLAN §8.3, absorbed per §13.3 — phase-1 report)

The plan's assumed coordinates (direct guava `32.1.2-jre`, guice pin at the same
version) do not hold: `guice-parent 7.0.0` (and `6.0.0`) pins guava
**31.0.1-jre** (verified from the guice-parent poms; no 6.x/7.x guice release pins
32.x). Per §8.3 **set C** ("any pair (direct A with upgrade, fixed B) where
`dependency:tree` proves B transitively pins A at ≠ target"), this pair was
chosen with verified coordinates: A = guava `31.0.1-jre → 33.4.8-jre`, B = guice
`7.0.0`. The mechanism (convergence failure + pin/two-hop resolution) is the
fixture; the coordinates are the replaceable part.

## Expected outcome (also `expected-outcome.yml`)

`UpgradeComplete` via exactly one failed build then a two-hop plan:
`VersionStep(guava→33.4.8-jre, MANAGEMENT)` (pin in `dependencyManagement`),
then `VersionStep(guava→33.4.8-jre, DIRECT)`. Stages must include `Repairing`;
`maxAttempts: 12`.
