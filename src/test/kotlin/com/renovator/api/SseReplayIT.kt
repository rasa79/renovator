package com.renovator.api

import com.renovator.audit.RunAudit
import com.renovator.audit.TrajectoryEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * SSE replay-then-tail (PLAN Task 5.2, D12): a late subscriber sees replayed
 * trajectory lines first (by sequence number), then live tailed events with
 * monotonically increasing sequences, and the stream completes on the terminal
 * event. A subscriber to a finished run replays and closes immediately.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SseReplayIT {
    @LocalServerPort
    var port: Int = 0

    private val runId = "sse-it"

    private fun reset() {
        Files.deleteIfExists(Path.of("var/runs/$runId/trajectory.jsonl"))
        RunAudit.clear()
    }

    private fun emit(event: TrajectoryEvent) {
        RunAudit.runId = runId
        RunAudit.emit(event)
    }

    /** Read SSE data lines until [stop] or timeout; each line is the event JSON.
     *  [during] runs on a side thread AFTER the stream opens (the live-emitting
     *  side of the test). */
    private fun readStream(
        stop: (String) -> Boolean,
        timeoutSeconds: Int = 30,
        during: () -> Unit = {},
    ): List<String> {
        val tail = Thread { during() }
        tail.start()
        try {
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/runs/$runId/stream")).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val lines =
                response
                    .body()
                    .bufferedReader()
                    .lineSequence()
                    .iterator()
            val out = mutableListOf<String>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
            while (System.nanoTime() < deadline && lines.hasNext()) {
                val line = lines.next() ?: break
                if (line.startsWith("data:")) {
                    val data = line.removePrefix("data:").trim()
                    if (data.isNotEmpty()) {
                        out += data
                        if (stop(data)) {
                            break
                        }
                    }
                }
            }
            return readSse(stop, timeoutSeconds)
        } finally {
            tail.interrupt()
        }
    }

    private fun readSse(
        stop: (String) -> Boolean,
        timeoutSeconds: Int,
    ): List<String> {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/runs/$runId/stream")).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val lines =
            response
                .body()
                .bufferedReader()
                .lineSequence()
                .iterator()
        val out = mutableListOf<String>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        while (System.nanoTime() < deadline && lines.hasNext()) {
            val line = lines.next() ?: break
            if (line.startsWith("data:")) {
                val data = line.removePrefix("data:").trim()
                if (data.isNotEmpty()) {
                    out += data
                    if (stop(data)) {
                        break
                    }
                }
            }
        }
        response.body().close()
        return out
    }

    private fun seq(line: String): Long = Regex(""""seq"\s*:\s*(\d+)""").find(line)!!.groupValues[1].toLong()

    @Test
    fun `replayed events precede tailed live events with monotonically increasing sequence numbers`() {
        reset()
        try {
            // Two events already on disk: the replay set. The subscriber is late.
            emit(TrajectoryEvent.StageEntered("Analyzing"))
            emit(TrajectoryEvent.StageEntered("Planning"))
            // Emit a LIVE event shortly after the stream opens (the tail).
            val streamOpen = java.util.concurrent.CountDownLatch(1)
            val live =
                Thread {
                    streamOpen.await()
                    Thread.sleep(500)
                    emit(TrajectoryEvent.StageEntered("Applying"))
                }
            live.start()
            val data =
                readStream(stop = { it.contains("\"stage\":\"Applying\"") }) {
                    streamOpen.countDown()
                }

            live.join(3_000)

            assertTrue(data.size >= 3, "replay 2 + at least one tailed event: $data")
            assertEquals(
                listOf("Analyzing", "Planning", "Applying"),
                data.map { Regex(""""stage":"([A-Za-z]+)"""").find(it)!!.groupValues[1] },
                "replayed lines first, then the tailed event",
            )
            val seqs = data.map { seq(it) }
            assertEquals(seqs.sorted(), seqs, "the sequences are monotonic: $seqs")
            assertEquals((1L..seqs.size.toLong()).toList(), seqs, "sequences are 1-based consecutive: $seqs")
        } finally {
            reset()
        }
    }

    @Test
    fun `stream completes on the terminal event`() {
        reset()
        try {
            emit(TrajectoryEvent.StageEntered("Analyzing"))
            val data =
                readStream(stop = { it.contains("UpgradeComplete") }, timeoutSeconds = 20) {
                    Thread.sleep(500)
                    emit(TrajectoryEvent.Completed(terminal = "UpgradeComplete"))
                }
            assertTrue(data.last().contains("\"terminal\":\"UpgradeComplete\""), "terminal event delivered: $data")
            assertTrue(data.size >= 2, "replay + terminal: $data")
        } finally {
            reset()
        }
    }

    @Test
    fun `subscribing to a finished run replays and closes immediately`() {
        reset()
        try {
            emit(TrajectoryEvent.StageEntered("Analyzing"))
            emit(TrajectoryEvent.Completed(terminal = "UpgradeComplete"))
            val data = readStream(stop = { it.contains("UpgradeComplete") }, timeoutSeconds = 10) { }
            assertTrue(data.size == 2, "replay only, then closed: $data")
        } finally {
            reset()
        }
    }
}
