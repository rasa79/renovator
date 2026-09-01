#!/usr/bin/env bash
# Task 4.5 demo (PLAN D10, C-5): kill-and-resume through TWO JVM sessions.
#   Phase 1 (`#phaseKill`): the upgrade runs against fixture-api-removal and is
#     CUT mid-flight while the state machine is inside the Applying frame (the
#     framework's early termination at maxActions=4 — the SIGKILL equivalent:
#     no graceful completion, no finalize, no UpgradeComplete). The typed
#     snapshot is written to var/runs/{runId}/process.json and the JVM exits.
#   Phase 2 (`#phaseResume`): a FRESH JVM re-seeds from that snapshot
#     (RunService.resume) and the continuation runs: apply -> build fails (the
#     migration breakage) -> repair -> apply -> green -> UpgradeComplete.
#
# Output (phase-4 report transcript):
#   KILLED at stage Applying (pid ...)
#   RESUMED run kill-demo -> UpgradeComplete
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RUN_ID="${RENOVATOR_DEMO_RUN_ID:-kill-demo}"

echo "== Kill-and-resume demo (PLAN Task 4.5, D10) =="
echo "run id: $RUN_ID"
echo
echo "Phase 1: run the upgrade; cut it mid-Applying (early termination = SIGKILL"
echo "equivalent); the typed snapshot is the ONLY survivor of this JVM session."
echo
./mvnw -q -Pdocker-it test -Dtest="KillResumeIT#phaseKill*"

echo
echo "Phase 2: fresh JVM; resume from the persisted snapshot."
echo
./mvnw -q -Pdocker-it test -Dtest="KillResumeIT#phaseResume*"
