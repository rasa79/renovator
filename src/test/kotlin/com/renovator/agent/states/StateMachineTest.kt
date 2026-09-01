package com.renovator.agent.states

import com.renovator.domain.BuildResult
import com.renovator.domain.ChangeScope
import com.renovator.domain.DependencyTarget
import com.renovator.domain.PlanStep
import com.renovator.domain.RepoModel
import com.renovator.domain.RunRequest
import com.renovator.domain.TestResult
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import com.renovator.domain.VersionChange
import com.renovator.domain.WorkspaceVerdict
import com.renovator.execution.Excerpt
import com.renovator.execution.WorkspaceRef
import com.renovator.execution.WorkspaceSnapshot
import com.renovator.validation.ValidatedPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Task 4.1 state-machine tests (C-2 semantics): the loop shape, the scoping
 * contract, and the loop-carried data rule. These drive the state TRANSITIONS
 * directly (unit-level); the end-to-end loop behavior is proven by the repair
 * and two-hop ITs (4.2/4.3).
 */
class StateMachineFixture {
    val goal = UpgradeGoal(targets = listOf(DependencyTarget("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0")))

    // The REAL fixture: ApplyValidatedChangesAction copies it (D7) and the executor
    // stages the plan onto the copy — an empty temp dir cannot stand in for this.
    val runRequest = RunRequest(repoPath = Path.of("fixtures/fixture-clean"), goal = goal)
    val repoModel = RepoModel(emptyList(), emptyList(), "17")
    val plan =
        UpgradePlan(
            steps =
                listOf(
                    PlanStep.VersionStep(VersionChange("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0", ChangeScope.DIRECT)),
                ),
            rationale = "bump",
        )
    val validatedPlan: ValidatedPlan = ValidatedPlan.create(plan, listOf("L1:x", "L2:y", "L3:z"))
    val snapshot = WorkspaceSnapshot(WorkspaceRef(Files.createTempDirectory("snap-")), "hash")

    companion object {
        /** A patch the REAL pipeline accepts: whitelisted src path, exact diff
         *  generated from the fixture's actual content (L2 applies it against the
         *  same file), code (not pom) — so the state transition tests assert the
         *  accepted path, not a validation rejection. */
        val patch: com.renovator.domain.CodePatch by lazy {
            val path = "src/main/java/com/example/clean/StringTools.java"
            val original =
                Files.readString(Path.of("fixtures/fixture-clean").resolve(path))
            val modified =
                original.replace(
                    "    private StringTools() {\n    }",
                    "    private StringTools() {\n    }\n\n    // repair marker\n",
                )
            val diff =
                com.github.difflib.UnifiedDiffUtils
                    .generateUnifiedDiff(
                        "a/$path",
                        "b/$path",
                        original.lines(),
                        com.github.difflib.DiffUtils
                            .diff(original.lines(), modified.lines()),
                        3,
                    ).joinToString("\n")
            com.renovator.domain.CodePatch(path, diff, "fix")
        }
    }
}

class StateLoopTest {
    @Test
    fun `repairing loops back to applying and terminates within budget`() {
        val f = StateMachineFixture()
        val patch = StateMachineFixture.patch
        val verdict =
            WorkspaceVerdict(
                BuildResult(
                    success = false,
                    failedGoals = listOf("[maven-compiler-plugin:compile]"),
                    log = Excerpt.of("boom"),
                    durationMs = 1,
                ),
                TestResult(0, 1, emptyList()),
            )
        val repairing =
            Repairing(
                goal = f.goal,
                runRequest = f.runRequest,
                repoModel = f.repoModel,
                validatedPlan = f.validatedPlan,
                snapshot = f.snapshot,
                failedVerdict = verdict,
                attempts = emptyList(),
            )

        // The loop transition: Repairing.validatePatch returns Applying carrying the
        // patch (clearBlackboard = true on the action, per C-2 semantics). The REAL
        // validation pipeline runs here (L1/L2/L3): a valid code patch is accepted.
        val applying = repairing.validatePatch(patch)
        assertTrue(applying is Applying, "repairing must loop back to applying, got $applying")
        assertEquals(patch, (applying as Applying).pendingPatch?.patch, "the raw patch rides the state frame")

        // The loop continues: Applying.applyValidatedChanges -> Verifying (the next
        // judge cycle). Budget: the state carries the attempt data; the machine
        // terminates when a verdict is green (verified end-to-end in 4.2/4.3).
        val verifying = applying.applyValidatedChanges()
        assertTrue(verifying is Verifying, "applying must transition to verifying, got $verifying")
    }
}

class StateScopingTest {
    @Test
    fun `only the current state's actions are plannable while in Repairing`() {
        val f = StateMachineFixture()
        val verdict =
            WorkspaceVerdict(
                BuildResult(false, listOf("[maven-compiler-plugin:compile]"), Excerpt.of("x"), 1),
                TestResult(0, 1, emptyList()),
            )
        val repairing = Repairing(f.goal, f.runRequest, f.repoModel, f.validatedPlan, f.snapshot, verdict, emptyList())
        // Repairing declares exactly its recovery actions — no apply/runBuild here
        // (those belong to Applying/Verifying; C-2 state scoping).
        val repairingActions =
            Repairing::class.java.declaredMethods
                .filter { it.isAnnotationPresent(com.embabel.agent.api.annotation.Action::class.java) }
                .map { it.name }
                .toSet()
        assertEquals(setOf("diagnoseFailure", "proposePatch", "validatePatch"), repairingActions)
        // And the inverse: Applying/Verifying do not expose recovery actions.
        val applyingActions =
            Applying::class.java.declaredMethods
                .filter { it.isAnnotationPresent(com.embabel.agent.api.annotation.Action::class.java) }
                .map { it.name }
                .toSet()
        assertEquals(setOf("applyValidatedChanges"), applyingActions)
    }
}

class StateCarriedDataTest {
    @Test
    fun `attempt records survive the loop because they ride the state instance`() {
        val f = StateMachineFixture()
        val attempt =
            com.renovator.domain.AttemptRecord(
                planRationale = "first try",
                rejectedAt = null,
                buildFailedGoals = listOf("[x:y]"),
                validationRejections = emptyList(),
            )
        // Data rides the state: constructing the next frame copies the payload.
        val repairedRepairing =
            Repairing(
                f.goal,
                f.runRequest,
                f.repoModel,
                f.validatedPlan,
                f.snapshot,
                WorkspaceVerdict(BuildResult(false, listOf("a"), Excerpt.of("x"), 1), TestResult(0, 1, emptyList())),
                listOf(attempt),
            )
        assertEquals(1, repairedRepairing.attempts.size)
        // The Applying frame carries forward the validated plan + patch (loop data).
        val patch = StateMachineFixture.patch
        val applying = repairedRepairing.validatePatch(patch)
        assertNotNull((applying as Applying).pendingPatch)
        assertEquals(patch, applying.pendingPatch?.patch, "the loop carries the patch in the state frame")
        assertEquals(repairedRepairing.validatedPlan, applying.validatedPlan, "the validated plan must survive the loop in the state frame")
    }
}
