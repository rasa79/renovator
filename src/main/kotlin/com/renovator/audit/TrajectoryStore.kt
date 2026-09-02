package com.renovator.audit

import com.renovator.validation.ProposalJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

// LEARN[016] The audit trail is a feature: persist every decision before any UI exists
// Why this way: a trajectory is the ONE artifact that answers "what did the agent
//   decide, in what order, against what evidence?" — and it is the only such artifact
//   that survives an agent whose behavior is emergent. So it is written FIRST, not as
//   a UI afterthought: every proposal, plan attempt, validation outcome, build, and
//   escalation is appended (typed, sequence-numbered, JSON) the moment it happens,
//   long before any stream or REST surface existed (the SSE and CLI read it later —
//   Phase 5). The write is append-only and interrupted-write-safe (the sequence
//   counter walks backwards past a partial trailing line), so even a killed JVM
//   leaves a coherent story (D14). The immutable-log+tailer split (LEARN[015]) is the
//   direct consequence: replay is a file read, the live tail is only a bonus.
// Good sides: the eval harness judges runs from the trajectory (D13) without touching
//   the agent's internals; a reviewer can replay any run exactly; typed events carry
//   the structured reason (a ValidationRejection names the check + the content), so
//   "show me every decision" is queryable (Task 6.4), not prose.
// Drawbacks: the file can grow (the LLM-call attempts repeat); the string-matching
//   filters (Task 6.4) are cheap but not schema-typed (the JSON is the parity); and
//   the trail is single-process (per-JVM) — a distributed agent needs a log bus (out
//   of scope, KL-01).
// Concept: think of it as a flight recorder plus a black box — record everything first;
//   reconstruct and judge later. The recorder is not the plane; it is what lets anyone
//   explain the plane.
// See also: PLAN §5 / Tasks 3.4 & 6.4 (D13, D14), LEARN[015] (replay-then-tail), LEARN[014]

/**
 * Append-only JSONL trajectory (PLAN Task 3.4 / D14): one line per typed event at
 * `var/runs/{runId}/trajectory.jsonl`, each with a monotonic sequence number.
 * "Show me every decision the agent made" — this is the audit trail.
 */
class TrajectoryStore(
    private val runsDir: Path = Path.of("var/runs"),
) {
    fun append(
        runId: String,
        event: TrajectoryEvent,
    ): Long {
        val runDir = runsDir.resolve(runId)
        Files.createDirectories(runDir)
        val file = runDir.resolve("trajectory.jsonl")
        val sequence = sequenceFor(file)
        val line = ProposalJson.mapper.writeValueAsString(Envelope(sequence, event))
        Files.writeString(file, "$line\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        return sequence
    }

    fun read(runId: String): List<String> {
        val file = runsDir.resolve(runId).resolve("trajectory.jsonl")
        return if (Files.exists(file)) Files.readAllLines(file) else emptyList()
    }

    /** The last sequence number in the file; each line is atomic (append-only). */
    private fun sequenceFor(file: Path): Long {
        if (!Files.exists(file)) {
            return 1L
        }
        // Walk backwards from the end: an interrupted write may leave a partial
        // trailing line, so the sequence continues from the last COMPLETE line.
        for (line in Files.readAllLines(file).asReversed()) {
            val seq = parseSequence(line) ?: continue
            return seq + 1L
        }
        return 1L
    }

    private fun parseSequence(line: String): Long? {
        // Only COMPLETE lines count: an interrupted write leaves a partial line on
        // disk — such a line must not advance the sequence counter.
        if (!line.trimEnd().endsWith("}")) {
            return null
        }
        return Regex(""""seq"\s*:\s*(\d+)""")
            .find(line)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
    }

    /** Typed envelope: the static type is the sealed interface, so Jackson emits the
     *  polymorphic type tag (eventType) into every line. */
    private data class Envelope(
        val seq: Long,
        val event: TrajectoryEvent,
    )
}
