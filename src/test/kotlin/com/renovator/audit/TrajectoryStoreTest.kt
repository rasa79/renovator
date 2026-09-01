package com.renovator.audit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TrajectoryStoreTest {
    @Test
    fun `appends typed events in insertion order`() {
        val tmp = Files.createTempDirectory("traj-")
        try {
            val store = TrajectoryStore(tmp)
            store.append("run-1", TrajectoryEvent.StageEntered("Analyzing"))
            store.append(
                "run-1",
                TrajectoryEvent.BuildObserved(success = false, failedGoals = listOf("[maven-compiler-plugin:compile]"), durationMs = 55),
            )
            val lines = store.read("run-1")
            assertEquals(2, lines.size)
            assertTrue(lines[0].contains("StageEntered") && lines[0].contains(""""seq":1"""), lines[0])
            assertTrue(lines[1].contains("BuildObserved") && lines[1].contains(""""seq":2"""), lines[1])
            assertTrue(lines[1].contains("maven-compiler-plugin"), "typed fields must serialize")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `last line survives an interrupted write intact`() {
        val tmp = Files.createTempDirectory("traj-")
        try {
            val store = TrajectoryStore(tmp)
            store.append("run-1", TrajectoryEvent.StageEntered("Analyzing"))
            // simulate an interrupted write: append a partial line, then a new event
            val file = tmp.resolve("run-1/trajectory.jsonl")
            Files.writeString(file, "{\"seq\":2,\"partial", java.nio.file.StandardOpenOption.APPEND)
            val seq = store.append("run-1", TrajectoryEvent.Completed("UpgradeComplete"))
            // sequence continues from the last COMPLETE line, and the new line is intact JSON
            assertEquals(2L, seq, "sequence must continue from the last complete line")
            val last = store.read("run-1").last()
            assertTrue(last.contains("Completed") && last.contains(""""seq":2"""), last)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}

class RunRegistryTest {
    @Test
    fun `second concurrent run is rejected`() {
        val registry = RunRegistry()
        assertTrue(registry.start("run-A"))
        assertFalse(registry.start("run-A"), "a second concurrent run of the same id must be rejected")
        assertTrue(registry.start("run-B"), "a DIFFERENT run id must be accepted (single-run is per-process identity)")
        registry.finish("run-A")
        assertTrue(registry.start("run-A"), "after finish the id may start again")
    }
}
