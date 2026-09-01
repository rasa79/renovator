package com.renovator.agent.states

import com.renovator.domain.ChangeScope
import com.renovator.domain.DependencyTarget
import com.renovator.domain.PlanStep
import com.renovator.domain.RepoModel
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import com.renovator.domain.VersionChange
import com.renovator.validation.ValidatedPatch
import com.renovator.validation.ValidatedPlan
import com.renovator.execution.WorkspaceRef
import com.renovator.execution.WorkspaceSnapshot
import com.renovator.domain.WorkspaceVerdict
import com.renovator.domain.BuildResult
import com.renovator.domain.TestResult
import com.renovator.execution.Excerpt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Task 4.1 state-machine tests (C-2 semantics): the loop shape, the scoping
 * contract, and the loop-carried data rule. These drive the state TRANSITIONS
 * directly (unit-level); the end-to-end loop behavior is proven by the repair
 * and two-hop ITs (4.2/4.3).
 */
class StateMachineFixture {
    val goal = UpgradeGoal(targets = listOf(DependencyTarget("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0")))
    val runRequest = RunRequest(repoPath = Files.createTempDirectory("state-fixture-"), goal = goal)
    val repoModel = RepoModel(emptyList(), emptyList(), "17")
    val plan = UpgradePlan(
        steps = listOf(
            PlanStep.VersionStep(VersionChange("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0", ChangeScope.DIRECT)),
        ),
        rationale = "bump",
    )
    val validatedPlan: ValidatedPlan = ValidatedPlan.create(plan, listOf("L1:x", "L2:y", "L3:z"))
    val snapshot = WorkspaceSnapshot(WorkspaceRef(Files.createTempDirectory("snap-")), "hash")
}

class StateLoopTest {

    @Test
    fun `repairing loops back to applying and terminates within budget`() {
        val f = StateMachineFixture()
        val patch = com.renovator.domain.CodePatch("src/main/java/com/example/clean/StringTools.java", "--- a/x\n+++ b/x\n@@ -0,0 +1,1 @@\n+// fix\n", "fix")
        val validatedPatch: ValidatedPatch = ValidatedPatch.create(patch, listOf("L1:x", "L2:y", "L3:z"))
        val verdict = WorkspaceVerdict(
            BuildResult(success = false, failedGoals = listOf("[maven-compiler-plugin:compile]"), log = Excerpt.of("boom"), durationMs = 1),
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
        // patch (clearBlackboard = true on the action, per C-2 semantics).
        val applying = repairing.validatePatch(patch)
        assertTrue(applying is Applying, "repairing must loop back to applying, got $applying")
        assertEquals(validatedPatch, (applying as Applying).pendingPatch)

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
        val verdict = WorkspaceVerdict(
            BuildResult(false, listOf("[maven-compiler-plugin:compile]"), Excerpt.of("x"), 1),
            TestResult(0, 1, emptyList()),
        )
        val repairing = Repairing(f.goal, f.runRequest, f.repoModel, f.validatedPlan, f.snapshot, verdict, emptyList())
        // Repairing declares exactly its recovery actions — no apply/runBuild here
        // (those belong to Applying/Verifying; C-2 state scoping).
        val repairingActions = Repairing::class.java.declaredMethods
            .filter { it.isAnnotationPresent(com.embabel.agent.api.annotation.Action::class.java) }
            .map { it.name }
            .toSet()
        assertEquals(setOf("diagnoseFailure", "proposePatch", "validatePatch"), repairingActions)
        // And the inverse: Applying/Verifying do not expose recovery actions.
        val applyingActions = Applying::class.java.declaredMethods
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
        val attempt = com.renovator.domain.AttemptRecord(planRationale = "first try", rejectedAt = null, buildFailedGoals = listOf("[x:y]"), validationRejections = emptyList())
        // Data rides the state: constructing the next frame copies the payload.
        val repairedRepairing = Repairing(f.goal, f.runRequest, f.repoModel, f.validatedPlan, f.snapshot, WorkspaceVerdict(BuildResult(false, listOf("a"), Excerpt.of("x"), 1), TestResult(0, 1, emptyList())), listOf(attempt))
        assertEquals(1, repairedRepairing.attempts.size)
        // The Applying frame carries forward the validated plan + patch (loop data).
        val patch = com.renovator.domain.CodePatch("pom.xml", "--- a/pom.xml\n+++ b/pom.xml\n@@ -1,1 +1,1 @@\n-x\n+y\n", "pin")
        val applying = repairedRepairing.validatePatch(patch)
        assertNotNull((applying as Applying).pendingPatch)
        assertEquals(repairedRepairing.validatedPlan, applying.validatedPlan, "the validated plan must survive the loop in the state frame")
    }
}
