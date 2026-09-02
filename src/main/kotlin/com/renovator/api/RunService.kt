package com.renovator.api

import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.renovator.agent.states.Applying
import com.renovator.audit.RunAudit
import com.renovator.audit.RunRegistry
import com.renovator.audit.TrajectoryEvent
import com.renovator.audit.TrajectoryStore
import com.renovator.config.ProcessOptionsFactory
import com.renovator.domain.HumanDecision
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeGoal
import com.renovator.persistence.JsonFileAgentProcessRepository
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// LEARN[015] The observable loop: replay-then-tail, and WaitFor as the BPMN human task
// Why this way: (part 1 — the stream, D12) Sentinel's stream lets a late subscriber see
//   the WHOLE story — it replays the run's events in order and then tails the live ones.
//   Ours does the same with one source of truth: the trajectory FILE (append-only,
//   sequence-numbered, interrupted-write safe) is replayed in full for a new subscriber;
//   the app event bus (TrajectoryBus) is the live tail only. That split is the design:
//   a subscriber that misses bus events can always re-read the file, and the file never
//   depends on having subscribers. The plan's "application event bus" is exactly this
//   bus — the publish hook lives in RunAudit.emit, the heartbeat keeps idle streams
//   alive, and the stream completes on the terminal event (SseReplayIT proves the
//   ordering, the monotonic sequences, and the immediate close for finished runs).
//   (part 2 — the gate, D11) WaitFor is a BPMN human task in disguise —
//   the process parks, the outside world answers, the blackboard resumes it. The
//   framework's WaitFor parks (WAITING, proven in Phase 4); what 1.5.1 does NOT have
//   is a public programmatic answer path (KL-09: submitFormAndResumeProcess was
//   removed in 0.3.3+ — issue #1447, jar-verified), so OUR REST layer stands in for
//   the shell's form renderer: submitDecision reads the parked gate, terminates the
//   parked shell, and re-seeds the machine with [gate, decision] — the decision's
//   VALUE picks the continuation (approve/reject conditions), the trajectory stays one
//   story (Resumed marker, no repeated Analyze), and the park closes (gateUnresolved).
//   Good sides: subscribers replay from an immutable log (no ordering races); the
//   gate loop is testable end-to-end without the shell (ApprovalGateIT through the
//   real service path); the decision is typed and the comment rides the repair.
//   Drawbacks: the tail is in-memory (a restart re-replays from the file — fine, the
//   file is the truth); the "parked shell terminated + re-seed" is not a true resume
//   of the same process object (KL-09 permanent); and the bus is per-JVM (no fan-out
//   across processes — the CLI is single-host by scope, KL-01).
//   Concept: think of the trajectory as a write-ahead log and the SSE stream as the
//   tailer; think of the gate as a BPMN user task whose "assignee" is the HTTP layer.
// See also: PLAN §5, PLAN Tasks 5.2/5.3 (D11, D12), KL-09, LEARN[012], LEARN[014]

/** Typed conflict: a second concurrent run (KL-01) — the controller maps to 409. */
class ConflictException(
    val runId: String?,
    message: String,
) : RuntimeException(message)

/**
 * Run orchestration (PLAN Task 5.1): submit runs asynchronously on a bounded
 * executor, enforce the single-run gate (KL-01 → [ConflictException] → 409),
 * expose typed status/trajectory reads, and RESUME killed runs (Task 4.5).
 * The REST layer (RunController) is a thin typed shell over this.
 */
@Component
class RunService(
    private val platform: AgentPlatform,
    /** Explicit agent (the IT/demo path: the dummy platform does not auto-deploy
     *  agents, so the test hands the metadata in; production leaves it null and
     *  the platform deployment is resolved lazily at submit time). */
    private val explicitAgent: Agent? = null,
    private val repository: JsonFileAgentProcessRepository = JsonFileAgentProcessRepository(),
    private val optionsFactory: ProcessOptionsFactory = ProcessOptionsFactory(),
    private val registry: RunRegistry = RunRegistry(),
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(1) { r -> Thread(r, "renovator-run").apply { isDaemon = true } },
) : AutoCloseable {
    private val processes = ConcurrentHashMap<String, AgentProcess>()
    private val store: TrajectoryStore = TrajectoryStore()

    /** The agent to run: the deployed @Agent beans are exposed via the platform
     *  (the framework registers the metadata wrappers there, not as Agent-typed
     *  beans), and the deployment completes after this bean is constructed —
     *  hence lazy resolution at submit time. */
    private fun agent(): Agent =
        explicitAgent
            ?: platform.agents().firstOrNull { it.description.contains("Renovator") }
            ?: platform.agents().firstOrNull()
            ?: error("no agent deployed on the platform")

    fun submit(
        goal: UpgradeGoal,
        runRequest: RunRequest,
        plannerType: com.embabel.agent.api.common.PlannerType? = com.embabel.agent.api.common.PlannerType.GOAP,
    ): String {
        val runId =
            registry.tryBegin()
                ?: throw ConflictException(null, "a run is already active (single-run enforcement, KL-01)")
        val options = optionsFactory.processOptions(plannerType)
        val process = platform.createAgentProcess(agent(), options, mapOf("goal" to goal, "runRequest" to runRequest))
        processes[runId] = process
        executor.execute {
            try {
                RunAudit.runId = runId
                process.run()
                repository.update(process)
            } finally {
                RunAudit.clear()
                registry.finish(runId)
                // The process stays registered: completed runs keep their status
                // readable, and a parked (WAITING) run MUST be reachable — the
                // decision layer reads it (pendingDecision) and the HITL resume
                // (submitDecision) terminates it.
            }
        }
        return runId
    }

    fun status(runId: String): RunStatus {
        val live = processes[runId]
        val frame =
            live
                ?.objects
                ?.filterIsInstance<com.renovator.agent.states.UpgradeStage>()
                ?.lastOrNull()
                ?.javaClass
                ?.simpleName
        return RunStatus(
            runId = runId,
            status = live?.status?.name ?: "PERSISTED",
            stage = frame,
            attempts = store.read(runId).count { it.contains("PlanAttempted") },
        )
    }

    fun trajectory(
        runId: String,
        type: String? = null,
        stage: String? = null,
    ): List<String> =
        store.read(runId).filter { line ->
            (type == null || line.contains("\"eventType\":\"$type\"")) &&
                (stage == null || line.contains("\"stage\":\"$stage\""))
        }

    /** The live process, if the run is still executing (HITL layers). */
    fun liveProcess(runId: String): AgentProcess? = processes[runId]

    /** Continue a killed run: snapshot -> re-seed -> run to completion. */
    fun resume(
        runId: String,
        plannerType: com.embabel.agent.api.common.PlannerType? = com.embabel.agent.api.common.PlannerType.GOAP,
    ): AgentProcess {
        val snapshot =
            repository.load(runId)
                ?: error("no snapshot for run $runId (nothing was persisted for it)")
        // KL-08: the resume re-enters AT THE LAST APPLY — the re-seed is the
        // validated plan payload (+ pending patch), regardless of the frame the
        // snapshot was taken in. Before the first apply there is no payload;
        // such a snapshot is rejected rather than silently restarted.
        require(snapshot.planSteps.isNotEmpty()) {
            "run $runId has no applied payload yet (frame '${snapshot.frame}'); only runs past the first apply are resumable (KL-08)"
        }
        val applying =
            Applying(
                goal = snapshot.goal,
                runRequest = snapshot.runRequest,
                repoModel = snapshot.repoModel,
                validatedPlan = snapshot.validatedPlan(),
                pendingPatch =
                    snapshot.pendingPatch?.let {
                        com.renovator.agent.actions.ValidatePatchAction
                            .validate(it, snapshot.runRequest)
                            .let { outcome ->
                                when (outcome) {
                                    is com.renovator.agent.actions.ValidatePatchAction.Outcome.Accepted -> {
                                        outcome.patch
                                    }

                                    is com.renovator.agent.actions.ValidatePatchAction.Outcome.Rejected -> {
                                        error("pending patch no longer validates after resume: ${outcome.rejection.reason}")
                                    }
                                }
                            }
                    },
            )
        val options = optionsFactory.processOptions(plannerType)
        val process = platform.createAgentProcess(agent(), options, mapOf("applying" to applying))
        RunAudit.runId = runId
        RunAudit.emit(
            TrajectoryEvent.Resumed(
                reason = "kill-and-resume: JVM died during frame ${snapshot.frame}",
                frame = snapshot.frame,
            ),
        )
        process.run()
        repository.update(process)
        return process
    }

    /** The awaited form: the gate (or blocker) a WAITING run is parked at. */
    fun pendingDecision(runId: String): PendingDecision? {
        val live = processes[runId]
        return live
            ?.objects
            ?.filterIsInstance<com.renovator.agent.states.GatePending>()
            ?.lastOrNull()
            ?.let {
                PendingDecision(
                    runId = runId,
                    kind = "approval",
                    question = "approval required (${it.gateKind.name.lowercase().replace('_', '-')}): ${it.validatedPlan.plan.rationale}",
                    approved = null,
                    attempts = 0,
                )
            } ?: live
            ?.objects
            ?.filterIsInstance<com.renovator.domain.UpgradeBlocker>()
            ?.lastOrNull()
            ?.let {
                PendingDecision(
                    runId = runId,
                    kind = "plan-space",
                    question = it.humanQuestion,
                    approved = null,
                    attempts = it.attempts.size,
                )
            }
    }

    /** Submit a human decision to a WAITING run (PLAN Task 5.3, C-6 fallback —
     *  KL-09 PERMANENT: Embabel 1.5.1 exposes no public programmatic WaitFor
     *  submission API (the historical submitFormAndResumeProcess was removed;
     *  github.com/embabel/embabel-agent#1447 — the phase-5 report quotes the
     *  evidence). The resume continues the run under the SAME run id: the parked
     *  shell is terminated and a fresh process re-seeds the gate + decision, so
     *  the park closes (gateUnresolved) and the planner picks approve/reject —
     *  and the trajectory stays one story (a Resumed marker, no repeated Analyze).
     *
     * // TODO(review) KL-09: PERMANENT — the programmatic WaitFor submission API
     * // does not exist in Embabel 1.5.1 (submitFormAndResumeProcess removed in
     * // 0.3.3+; github.com/embabel/embabel-agent#1447; verified by jar search).
     * // The C-6 fallback (terminate + re-seed with the decision) is the
     * // supported pattern; the phase-5 report quotes the end-to-end evidence. */
    fun submitDecision(
        runId: String,
        decision: HumanDecision,
    ): RunStatus {
        val live = processes[runId]
        val gate =
            live
                ?.objects
                ?.filterIsInstance<com.renovator.agent.states.GatePending>()
                ?.lastOrNull()
                ?: throw IllegalArgumentException("run $runId is not parked at an approval gate (no decision is pending)")
        live?.terminateAgent("human decision (${decision.approved})")
        // The parked shell's executor thread releases the slot in a finally a few
        // ms after the park; wait it out (the decision is the same run, not a new
        // concurrent one — KL-01's gate stays for external submissions only).
        val slotDeadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(30)
        while (System.nanoTime() < slotDeadline && runId in registry.activeRunIds()) {
            Thread.sleep(50)
        }
        require(registry.start(runId)) { "run $runId is not resumable: another run is active (KL-01)" }
        processes[runId] =
            platform.createAgentProcess(
                agent(),
                optionsFactory.processOptions(com.embabel.agent.api.common.PlannerType.GOAP),
                mapOf("gate" to gate, "decision" to decision),
            )
        executor.execute {
            try {
                RunAudit.runId = runId
                RunAudit.emit(
                    TrajectoryEvent.Resumed(
                        reason = "human decision: ${if (decision.approved) "approved" else "rejected"} (${decision.comment})",
                        frame = "GatePending",
                    ),
                )
                processes[runId]?.run()
                processes[runId]?.let { repository.update(it) }
            } finally {
                RunAudit.clear()
                registry.finish(runId)
            }
        }
        return status(runId)
    }

    // LEARN[020] Drain the async executor: a test-created RunService must close()
    // Why this way: the agent runs on a single-thread DAEMON executor that is never
    //   naturally stopped. A test that submits a run and returns before that run
    //   fully drains leaves its worker thread executing into the NEXT test class —
    //   and because LlmChannel.actions, AgentTrace and RunAudit are process-global
    //   singletons, that still-running thread is swapped to whatever the next test
    //   sets: it drains the next test's ScriptedLlm plan queue (so the next run gets
    //   the wrong plan or an empty queue) or, once the next test resets the channel
    //   to the real LlmActions, it makes real LLM calls that fail
    //   (InvocationTargetException) and replan forever. That is a genuinely
    //   order-dependent failure — a fresh-clone reproduction exposed it because the
    //   clone's surefire order runs a RunService test before TwoHopReplanIT, while
    //   the main repo's order runs TwoHopReplanIT first and hides it. The fix: every
    //   test that constructs a RunService closes it in a finally, and close()
    //   terminates any still-live process (unblocking the worker) and shuts the
    //   executor down, so no agent thread survives into a later test class.
    // Good sides: a Hermetic, order-independent suite (the gate is reproducible);
    //   production (the Spring @Component) is unaffected — there is no "next test"
    //   to corrupt, and the executor lives for the JVM lifetime as intended.
    // Drawbacks: close() interrupts an in-flight run, so a test must assert whatever
    //   it needs before finally — which the tests already do (they poll to Done).
    // Concept: drain-or-leak — an async executor that nothing shuts down is a
    //   thread that can outlive its test.
    // See also: LEARN[015] (the RunService observable loop), PLAN Task 5.1,
    //   RunServiceIT, ApprovalGateIT, KillResumeIT
    override fun close() {
        // Terminate any still-live processes so the worker thread unblocks (the
        // agent's run() returns a terminal state rather than parking forever).
        processes.values.forEach { proc ->
            runCatching { proc.terminateAgent("RunService closed (drain)") }
        }
        executor.shutdownNow()
        processes.clear()
    }
}

/** The payload the decision layer renders (PLAN Task 5.3). */
data class PendingDecision(
    val runId: String,
    val kind: String,
    val question: String,
    val approved: Boolean?,
    val attempts: Int,
)

/** Typed status read (PLAN Task 5.1: `GET /api/runs/{id}`). */
data class RunStatus(
    val runId: String,
    val status: String,
    val stage: String?,
    val attempts: Int,
)
