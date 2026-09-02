#!/usr/bin/env bash
# Task 4.5 demo (D10, C-5): kill-and-resume through TWO JVM sessions, with a
# REAL kill -9.
#   Phase 1: the upgrade runs against fixture-api-removal (async, scripted LLM).
#     The moment the machine enters Applying, the phase-1 test writes the typed
#     snapshot (var/runs/{runId}/process.json), its own pid (pid.txt) and a
#     READY marker (ready-to-kill.txt), then HOLDS the JVM open while the run
#     thread keeps working (the sandbox build). THIS SCRIPT then runs
#     `kill -9 <pid>`: the JVM dies uncontrolled, mid-flight, with no graceful
#     shutdown — the snapshot and trajectory are the only survivors.
#   Phase 2: a fresh JVM re-seeds from that snapshot (RunService.resume) and
#     runs the continuation: apply -> build (fails once, migration breakage) ->
#     repair -> green -> UpgradeComplete.
#
# Output (phase-4 report transcript):
#   APPLYING FRAME PERSISTED (frame=..., snapshotAt=...) — pid NNNNN
#   kill command: kill -9 NNNNN   (verbatim)
#   killed at: <ISO timestamp>
#   RESUMED run kill-demo -> UpgradeComplete
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RUN_ID="${RENOVATOR_DEMO_RUN_ID:-kill-demo}"
MARKER="var/runs/$RUN_ID/ready-to-kill.txt"
PIDFILE="var/runs/$RUN_ID/pid.txt"
PHASE1_LOG="target/demo-kill-resume-phase1.log"

echo "== Kill-and-resume demo (PLAN Task 4.5, D10) =="
echo "run id: $RUN_ID"
echo
echo "Phase 1: start the upgrade; persist the snapshot; the JVM stays live and"
echo "mid-build until the SIGKILL below."
echo

rm -f "$MARKER" "$PIDFILE"
(./mvnw -q -Pdocker-it test -Dtest="KillResumeIT#phaseKill*" -DkillDemo=1 > "$PHASE1_LOG" 2>&1) &
MAVEN_PID=$!

# Poll for the READY marker (the Applying frame is persisted).
for _ in $(seq 1 600); do
    if [ -f "$MARKER" ]; then
        break
    fi
    if ! kill -0 "$MAVEN_PID" 2>/dev/null; then
        echo "FAIL: phase-1 maven exited before the marker; log tail:"
        tail -20 "$PHASE1_LOG"
        exit 1
    fi
    sleep 1
done
if [ ! -f "$MARKER" ]; then
    echo "FAIL: timed out waiting for the Applying frame; log tail:"
    tail -20 "$PHASE1_LOG"
    exit 1
fi

PID="$(cat "$PIDFILE")"
echo "snapshot marker (verbatim from phase 1):"
sed 's/^/  /' "$MARKER"
echo
echo "kill command: kill -9 $PID   # the JVM is mid-run at this moment"
echo "killed at: $(date -u +%Y-%m-%dT%H:%M:%S.%NZ)"
kill -9 "$PID"
wait "$MAVEN_PID" 2>/dev/null || true
echo "phase-1 JVM is dead (no graceful shutdown; maven exit was non-zero by the fork loss)"

echo
echo "Phase 2: fresh JVM; resume from the persisted snapshot."
echo
./mvnw -q -Pdocker-it test -Dtest="KillResumeIT#phaseResume*"
