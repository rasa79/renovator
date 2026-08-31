package com.renovator.execution

import com.renovator.config.RenovatorProperties
import com.renovator.domain.BuildResult
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

// LEARN[004] Reversibility: throwaway container + pristine copy; Docker CLI over Testcontainers
// Why this way: an agent that proposes build-affecting changes may be wrong, and being wrong
//   must be *cheap*. Every candidate build runs in a throwaway container from a pristine
//   copy of the workspace — the source tree is never mounted, never mutated, never locked.
//   The container is per-build, named, and hard-killed on timeout; the copy dies with the
//   run (temp dir). Testcontainers was considered and rejected: the lifecycle is implicit
//   and pulls test-scoped machinery into a production path whose whole point is
//   transparency — with the Docker CLI the exact command is inspectable, reproducible by
//   hand in a script, and debuggable from a shell (Plan §8.5, recorded decision).
// Good sides: retries are free (a new copy + container); the fixture hashes let tests
//   assert the source never changed; a broken build leaves no state behind; the same
//   command shows up verbatim in the demo scripts and phase reports.
// Drawbacks: one container launch per build (~seconds), the CLI path assumes a Docker
//   runtime with WSL2 integration (D15), and stdout must be drained concurrently or a
//   chatty Maven run can deadlock the pipe — the runner reads on a background thread.
// Concept: think "unit test for a build" — arrange (copy), act (container), assert
//   (exit code + typed BuildResult), destroy (--rm). The Excerpt budget exists because
//   the LLM context is finite: the planner reads head+tail, the judge keeps the full
//   log on disk; truncatedBytes makes "we cut something" explicit, never silent.
// See also: PLAN §8.5, PLAN D7, WorkspaceCopier.kt, Excerpt.kt
open class DockerSandboxRunner( // open: test seam (PLAN Task 1.6)
    private val sandbox: RenovatorProperties.Sandbox,
    /** Test-only seam: the IT substitutes a stub docker that sleeps (Documented seam,
     * Plan Task 1.6: "uses the runner's internal command hook with sleep 600"). */
    private val dockerCommand: List<String> = listOf("docker"),
) {
    /**
     * Runs `goals` in a throwaway container over [workspace] (PLAN §8.5 command shape):
     * `docker run --rm --memory=2g --cpus=2 -v <copy>:/work -v <cache-volume>:/m2
     * -w /work <image> mvn -q -Dmaven.repo.local=/m2/repository <goals>`
     *
     * DEVIATION (recorded, phase-1 report): the plan's literal `:ro` mount is
     * deliberately NOT used — Maven writes `target/` (and other basedir outputs)
     * into the workdir, so a read-only mount breaks every build. Reversibility is
     * unaffected: the mounted tree is a pristine throwaway copy, the source tree is
     * never mounted or mutated (asserted by DockerSandboxRunnerIT), and the copy is
     * deleted with the run.
     *
     * Hard timeout: the container is `docker kill`ed, the CLI process destroyed, and a
     * typed `BuildResult(success=false, failedGoals=["<timeout>"])` is returned.
     */
    open fun runBuild( // open: test seam documented in PLAN Task 1.6 (command hook)
        workspace: WorkspaceRef,
        goals: List<String>,
        timeout: Duration,
    ): BuildResult {
        val containerName = "renovator-sandbox-${UUID.randomUUID()}"
        val command =
            dockerCommand +
                listOf(
                    "run",
                    "--rm",
                    "--name",
                    containerName,
                    "--memory=${sandbox.memoryMb}m",
                    "--cpus=${sandbox.cpus}",
                    "-v",
                    "${workspace.path}:/work",
                    "-v",
                    "${sandbox.cacheVolume}:/m2",
                    "-w",
                    "/work",
                    sandbox.image,
                    "mvn",
                    "-Dmaven.repo.local=/m2/repository",
                ) + goals

        val started = System.nanoTime()
        val process = ProcessBuilder(command).redirectErrorStream(true).start()

        // Drain stdout on a background thread: if the pipe fills while we wait, the
        // container blocks and the timeout fires on a build that was still progressing.
        val output = StringBuilder()
        val drain =
            Thread {
                process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
            }
        drain.start()

        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            killContainer(containerName)
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            drain.join(1000)
            return BuildResult(
                success = false,
                failedGoals = listOf("<timeout>"),
                log = Excerpt.of(output.toString()),
                durationMs = timeout.toMillis(),
            )
        }
        drain.join(1000)
        val durationMs = (System.nanoTime() - started) / 1_000_000
        val log = Excerpt.of(output.toString())
        val success = process.exitValue() == 0
        return BuildResult(
            success = success,
            failedGoals = if (success) emptyList() else BuildResultParser.failedGoals(output.toString()),
            log = log,
            durationMs = durationMs,
        )
    }

    private fun killContainer(containerName: String) {
        try {
            val kill = ProcessBuilder(dockerCommand + listOf("kill", containerName)).start()
            if (!kill.waitFor(10, TimeUnit.SECONDS)) {
                kill.destroy()
            }
        } catch (e: Exception) {
            // The container may already be gone; the typed timeout result still stands.
        }
    }
}
