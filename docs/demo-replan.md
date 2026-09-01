# Two-hop replanning demo (PLAN Task 4.3 / §6.1 trace)

Regenerated from a **real run** (deterministic scripted-LLM docker-it run, not
hand-written): `scripts/demo-replan.sh` → `./mvnw -q -Pdocker-it test -Dtest=TwoHopReplanIT`.

**Run id:** `replan-it` (trajectory on disk: `var/runs/replan-it/trajectory.jsonl` —
the gate checks this id exists under `var/runs/`).

**Fixture:** `fixture-transitive-conflict` — direct `com.google.guava:guava` is
upgraded `31.0.1-jre → 33.4.8-jre`; `com.google.inject:guice:7.0.0` is FIXED and
transitively pins guava `31.0.1-jre`; enforcer `dependencyConvergence` is active.

**Drift disclosure (phase-1 environment note, reproduced here):** PLAN §6.1's
assumed `32.1.2-jre` transitive pin does not exist in any guice 6.x/7.x parent —
the verified pin is `31.0.1-jre` (per §8.3 set C the pair is replaceable while
the mechanism holds). The trace below carries the drift coordinates.

## The trace (verbatim trajectory lines, `var/runs/replan-it/trajectory.jsonl`)

```
  [1] stage entered: Analyzing
  [2] stage entered: Planning
  [3] PLAN ATTEMPTED (1 step(s)): single direct bump
  [5] VALIDATION: L1:plan-paths,L2:plan-diff,L3:versions accepted
  [6] stage entered: Applying
  [7] stage entered: Verifying
  [8] BUILD OBSERVED: FAILED ['[maven-enforcer-plugin:enforce]']
  [9] stage entered: Repairing
  [10] BuildDiagnosis PROPOSED: com.google.guava:guava; 31.0.1-jre
  [12] stage entered: Planning
  [13] PLAN ATTEMPTED (2 step(s)): pin the transitive guava, then bump the direct dependency
  [15] VALIDATION: L1:plan-paths,L2:plan-diff,L3:versions accepted
  [16] stage entered: Applying
  [17] stage entered: Verifying
  [18] BUILD OBSERVED: GREEN []
  [19] stage entered: Done
  [20] COMPLETED: UpgradeComplete
```

The two `PlanAttempted` lines verbatim (the reviewer-mandated quote):

```
{"seq":3,"event":{"eventType":"PlanAttempted","rationale":"single direct bump","stepCount":1,"at":"2026-09-01T11:09:51.291268805Z"}}
{"seq":13,"event":{"eventType":"PlanAttempted","rationale":"pin the transitive guava, then bump the direct dependency","stepCount":2,"at":"2026-09-01T11:09:54.506771236Z"}}
```

## What the trace shows

1. **Attempt 1** (`[3]`, one DIRECT step) — the single direct bump to
   `33.4.8-jre` fails at the enforcer's `validate` phase (`[8]` FAILED,
   `maven-enforcer-plugin:enforce`): dependencyConvergence sees
   `33.4.8-jre` (direct) against `31.0.1-jre` (via guice). The diagnosis
   (`[10]`) names both `guava` and the lagging
   `31.0.1-jre` pin and suggests `PIN_TRANSITIVE` + `MULTI_HOP` — the patch lane
   stays closed (no `PATCH_CODE` hint).
2. **The replan transition** (`[12]` stage entered: Planning — `Repairing.replan`
   hands the diagnosis back to Planning, the repair is a NEW PLAN, not a patch).
3. **Attempt 2** (`[13]`, two steps) — MANAGEMENT-scope pin (guava `33.4.8-jre`
   in `dependencyManagement`) THEN the DIRECT bump; both validated L1-L3 (`[15]`),
   applied, and the sandbox build goes GREEN with zero failed goals (`[18]`),
   reaching `UpgradeComplete` (`[20]`).
