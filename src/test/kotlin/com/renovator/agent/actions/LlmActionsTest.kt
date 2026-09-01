package com.renovator.agent.actions

import com.embabel.agent.test.unit.FakeOperationContext
import com.renovator.domain.ActionHint
import com.renovator.domain.BuildDiagnosis
import com.renovator.domain.BuildResult
import com.renovator.domain.ChangeScope
import com.renovator.domain.DependencyTarget
import com.renovator.domain.HintKind
import com.renovator.domain.PlanStep
import com.renovator.domain.RepoModel
import com.renovator.domain.ResolvedDependency
import com.renovator.domain.RootCause
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import com.renovator.domain.VersionChange
import com.renovator.execution.Excerpt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * LLM action tests against the canned mock (FakeOperationContext, C-9):
 * the fake returns the registered typed object directly — these tests assert the
 * action contract (prompt mapping, typed binding, canned two-hop plan).
 */
class LlmActionsTest {
    private val actions = LlmActions()
    private val repoModel =
        RepoModel(
            dependencies = listOf(ResolvedDependency("org.apache.commons", "commons-lang3", "3.12.0", direct = true)),
            enforcerRules = emptyList(),
            javaRelease = "17",
        )
    private val goal = UpgradeGoal(targets = listOf(DependencyTarget("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0")))

    private fun planOf(vararg steps: PlanStep) = UpgradePlan(steps = steps.toList(), rationale = "test")

    private fun samplePlan() =
        planOf(
            PlanStep.VersionStep(VersionChange("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0", ChangeScope.DIRECT)),
        )

    @Test
    fun `binds typed plan from canned response`() {
        val context = FakeOperationContext.create()
        context.expectResponse(samplePlan())
        val outcome = actions.proposePlan(context, repoModel, goal)
        assertTrue(outcome is LlmOutcome.Accepted, "$outcome")
        val plan = (outcome as LlmOutcome.Accepted).value
        assertEquals(1, plan.steps.size)
        assertEquals("org.apache.commons", (plan.steps[0] as PlanStep.VersionStep).change.groupId)
    }

    @Test
    fun `produces two-hop plan when canned response has management-scope step`() {
        val context = FakeOperationContext.create()
        context.expectResponse(
            planOf(
                PlanStep.VersionStep(VersionChange("com.google.guava", "guava", "31.0.1-jre", "33.4.8-jre", ChangeScope.MANAGEMENT)),
                PlanStep.VersionStep(VersionChange("com.google.guava", "guava", "31.0.1-jre", "33.4.8-jre", ChangeScope.DIRECT)),
            ),
        )
        val outcome = actions.proposePlan(context, repoModel, goal)
        val plan = (outcome as LlmOutcome.Accepted).value
        assertEquals(2, plan.steps.size)
        assertEquals(ChangeScope.MANAGEMENT, (plan.steps[0] as PlanStep.VersionStep).change.scope)
        assertEquals(ChangeScope.DIRECT, (plan.steps[1] as PlanStep.VersionStep).change.scope)
    }

    @Test
    fun `extracts root cause list from canned diagnosis`() {
        val context = FakeOperationContext.create()
        context.expectResponse(
            BuildDiagnosis(
                failedGoals = listOf("[maven-compiler-plugin:compile]"),
                rootCauses = listOf(RootCause("StringEscapeUtils", "removed from lang3")),
                suggestedActions = listOf(ActionHint(HintKind.PATCH_CODE, "replace the call")),
            ),
        )
        val build =
            BuildResult(success = false, failedGoals = listOf("[maven-compiler-plugin:compile]"), log = Excerpt.of("log"), durationMs = 10)
        val outcome = actions.diagnoseFailure(context, build)
        val diagnosis = (outcome as LlmOutcome.Accepted).value
        assertEquals("StringEscapeUtils", diagnosis.rootCauses.single().symbolOrArtifact)
        assertTrue(diagnosis.suggestedActions.any { it.kind == HintKind.PATCH_CODE })
    }

    @Test
    fun `binds patch with unified diff intact`() {
        val context = FakeOperationContext.create()
        val diff = "--- a/f\n+++ b/f\n@@ -1,1 +1,2 @@\n-x\n+y\n+z\n"
        context.expectResponse(com.renovator.domain.CodePatch("src/main/java/com/example/A.java", diff, "j"))
        val outcome = actions.proposePatch(context, BuildDiagnosis(emptyList(), emptyList(), emptyList()), "content")
        val patch = (outcome as LlmOutcome.Accepted).value
        assertEquals(diff, patch.unifiedDiff, "the diff must round-trip byte-identical")
    }
}
