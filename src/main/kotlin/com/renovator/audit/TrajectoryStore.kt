package com.renovator.audit

import com.renovator.validation.ProposalJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

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
