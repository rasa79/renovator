#!/usr/bin/env bash
# Task 4.3 demo backbone (PLAN Task 4.3, §6.1 trace): drives the two-hop
# replanning fixture with the scripted LLM via TwoHopReplanIT (a real docker-it
# run — deterministic, no live API), then extracts the typed trajectory events
# that realize the trace: attempt 1 (direct, fails dependencyConvergence) and
# attempt 2 (management pin + direct bump, green).
#
# Output: the §6.1 trace -- PlanAttempted lines with the DRIFT coordinates
# (guava 31.0.1-jre -> 33.4.8-jre; the plan's assumed 32.1.2-jre pin does not
# exist -- phase-1 environment note) and the two BuildObserved verdicts.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RUN_ID="${RENOVATOR_DEMO_RUN_ID:-replan-it}"

# The real run. -q keeps the demo output lean; failures are the IT's failures.
./mvnw -q -Pdocker-it test -Dtest=TwoHopReplanIT > /dev/null

echo "== Two-hop replanning trace (PLAN §6.1, fixture-transitive-conflict) =="
echo "run id: $RUN_ID"
echo

python3 - "$RUN_ID" <<'PY'
import json
import sys

run_id = sys.argv[1]
traj = f"var/runs/{run_id}/trajectory.jsonl"
with open(traj) as fh:
    lines = fh.read().splitlines()

for line in lines:
    envelope = json.loads(line)
    seq = envelope["seq"]
    e = envelope["event"]
    kind = e["eventType"]
    if kind == "StageEntered":
        print(f"  [{seq}] stage entered: {e['stage']}")
    elif kind == "PlanAttempted":
        print(f"  [{seq}] PLAN ATTEMPTED ({e['stepCount']} step(s)): {e['rationale']}")
    elif kind == "BuildObserved":
        verdict = "GREEN" if e["success"] else "FAILED"
        print(f"  [{seq}] BUILD OBSERVED: {verdict} {e['failedGoals']}")
    elif kind == "ValidationOutcome":
        verdict = "accepted" if e["accepted"] else "rejected"
        print(f"  [{seq}] VALIDATION: {e['checkName']} {verdict} {e['reason']}")
    elif kind == "ProposalReceived":
        print(f"  [{seq}] {e['kind']} PROPOSED: {e['summary'][:80]}")
    elif kind == "Completed":
        print(f"  [{seq}] COMPLETED: {e['terminal']}")
PY

echo
echo "Attempt 1 (direct, single bump) failed dependencyConvergence: guice 7.0.0 still"
echo "pins guava 31.0.1-jre while the direct dependency targets 33.4.8-jre."
echo "Attempt 2 (management pin, then direct bump) is green: the pin wins resolution."
