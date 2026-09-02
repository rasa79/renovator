package com.renovator.api

import com.renovator.audit.TrajectoryBus
import com.renovator.audit.TrajectoryStore
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SSE progress stream (PLAN Task 5.2, D12): replay-then-tail, exactly as
 * Sentinel's stream — a late subscriber sees the WHOLE story (the replayed
 * trajectory lines with their sequence numbers), then live events from the
 * application event bus, then completion on the terminal event. Heartbeat every
 * 15 s; a subscriber to an already-finished run replays and closes immediately.
 */
@RestController
class SseController(
    private val store: TrajectoryStore = TrajectoryStore(),
    private val executor: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "sse").apply { isDaemon = true } },
) {
    @GetMapping(value = ["/api/runs/{id}/stream"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @PathVariable id: String,
    ): SseEmitter {
        val emitter = SseEmitter(0L) // no timeout: heartbeat keeps the stream alive
        var lastSeq = 0L

        // 1. Replay: the file is the source of truth; each line carries its seq.
        for (line in store.read(id)) {
            val seq = parseSequence(line)
            if (seq != null) {
                lastSeq = seq
                emitter.send(SseEmitter.event().data(line, MediaType.APPLICATION_JSON))
            }
        }

        val finished = lastSeq > 0L && store.read(id).any { it.contains("\"eventType\":\"Completed\"") }
        if (finished) {
            emitter.complete()
            return emitter
        }

        // 2. Tail: live events from the bus (already-appended bytes).
        val sub =
            TrajectoryBus.subscribe(id) { event ->
                try {
                    if (event.seq > lastSeq) {
                        lastSeq = event.seq
                        emitter.send(SseEmitter.event().data(event.line, MediaType.APPLICATION_JSON))
                        if (event.line.contains("\"eventType\":\"Completed\"")) {
                            emitter.complete()
                        }
                    }
                } catch (_: Exception) {
                    // The client disconnected: the subscription is closed below.
                }
            }

        // 3. Heartbeat (15 s); a disconnected client's send throws -> close.
        val heartbeat =
            executor.submit {
                try {
                    while (true) {
                        Thread.sleep(15_000)
                        emitter.send(SseEmitter.event().comment("heartbeat"))
                    }
                } catch (_: Exception) {
                    // disconnect or complete
                } finally {
                    sub.close()
                }
            }
        emitter.onCompletion {
            heartbeat.cancel(true)
            sub.close()
        }
        emitter.onTimeout {
            heartbeat.cancel(true)
            sub.close()
        }
        emitter.onError {
            heartbeat.cancel(true)
            sub.close()
        }
        return emitter
    }

    private fun parseSequence(line: String): Long? {
        if (!line.trimEnd().endsWith("}")) {
            return null
        }
        return Regex(""""seq"\s*:\s*(\d+)""")
            .find(line)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
    }
}
