# Audit trail

> "Show me every decision the agent made." — the trajectory is that artifact (PLAN Task 3.4 / 6.4, D14). Every proposal, plan attempt, validation outcome, build, and escalation is persisted as a typed, sequence-numbered JSON line at `var/runs/{runId}/trajectory.jsonl`, the moment it happens — long before any UI existed (LEARN[016]); the SSE stream, CLI, and eval harness all read it.

## Worked example — `fixture-no-path`

A run whose target version `99.99.99` 404s forever. The agent must propose → L3-reject → re-propose five times → escalate honestly. The trail below is that whole story, one decision per line (run id `eval-fixture-no-path`, 25 events; elided fields shown by `…`).

```
{"seq":1,"event":{"eventType":"StageEntered","stage":"Analyzing",…}}
{"seq":2,"event":{"eventType":"StageEntered","stage":"Planning",…}}
{"seq":3,"event":{"eventType":"PlanAttempted","rationale":"nonexistent version: 99.99.99","stepCount":1,…}}
{"seq":4,"event":{"eventType":"LlmCall","action":"proposePlan","attempts":0,"rejected":false,…}}
{"seq":5,"event":{"eventType":"ValidationOutcome","checkName":"L3:version-exists","accepted":false,"reason":"version commons-lang3:99.99.99 does not exist in the version catalog",…}}
{"seq":6,"event":{"eventType":"StageEntered","stage":"Planning",…}}
… (the same propose→reject pair five times: seq 3-8, 9-14, 15-20 are the five attempts)
{"seq":21,"event":{"eventType":"ProposalReceived","kind":"UpgradeBlocker","summary":"plan space exhausted after 5 attempt(s): nonexistent version: 99.99.99; …",…}}
{"seq":22,"event":{"eventType":"StageEntered","stage":"Blocked",…}}
{"seq":23,"event":{"eventType":"Escalated","question":"Plan space exhausted: …",…}}
```

The **why** is in the typed fields: five `PlanAttempted`, five `ValidationOutcome(checkName="L3:version-exists", accepted=false)`, one `UpgradeBlocker` (with the full attempt history in its `summary`), one `Escalated`. No prose to interpret — a reader, or a script, sees exactly what happened.

## Querying it

The control API filters the trail by event type and stage (PLAN Task 6.4):

```
scripts/renovator trajectory eval-fixture-no-path --type ValidationRejection   # one line per attempt
# -> the five L3 rejection lines, each naming the check and the content
scripts/renovator trajectory eval-fixture-no-path --type StageEntered --stage Planning
# -> the five Planning re-entries (the honest-termination spiral)
```

At the HTTP layer the same is `GET /api/runs/{id}/trajectory?type=…&stage=…`.

## Properties

- **Append-only + interrupted-write safe**: the sequence counter walks backwards past a partial trailing line, so a killed JVM leaves a coherent story (LEARN[016], D14).
- **Typed**: every event serializes to a JSON object with a polymorphic `eventType` tag (ValidationRejection carries `checkName` + `reason`; a stage event carries `stage`).
- **One source of truth**: the file is replayed in full to late subscribers; the SSE tail and the eval harness read the same bytes (LEARN[015]).
