package com.renovator.agent.actions

import com.embabel.agent.api.common.createObject
import com.renovator.agent.llm.LlmAttempt
import com.renovator.agent.llm.LlmCall
import com.renovator.agent.prompt.PromptCatalog
import com.renovator.domain.BuildDiagnosis
import com.renovator.domain.BuildResult
import com.renovator.domain.CodePatch
import com.renovator.domain.RepoModel
import com.renovator.domain.UpgradeGoal
import com.renovator.domain.UpgradePlan
import com.renovator.domain.ValidationRejection
import com.renovator.validation.ProposalJson
import org.springframework.stereotype.Component

/**
 * The three LLM actions share one channel: typed binding via `createObject`
 * (D6/C-1) wrapped by [LlmCall] (KL-12 retry taxonomy). A binding failure is a
 * typed [ValidationRejection] inside [LlmOutcome] — never a raw exception and
 * never a blackboard object (LLMBindingStrictnessTest is the proof).
 *
 * NOTE (documented in the phase-3 report): the §6 palette table shows the
 * happy-path output types (UpgradePlan/BuildDiagnosis/CodePatch); the actual
 * return is [LlmOutcome] around them, because an action must be able to say
 * "the LLM answer was garbage" without lying about its type.
 */
sealed interface LlmOutcome<T> {
    data class Accepted<T>(
        val value: T,
        val attempts: List<LlmAttempt>,
    ) : LlmOutcome<T>

    data class Rejected<T>(
        val rejection: ValidationRejection,
        val attempts: List<LlmAttempt>,
    ) : LlmOutcome<T>
}

@Component
class LlmActions(
    private val call: LlmCall = LlmCall(),
    private val prompts: PromptCatalog = PromptCatalog(),
) {
    fun proposePlan(
        context: com.embabel.agent.api.common.OperationContext,
        repoModel: RepoModel,
        goal: UpgradeGoal,
    ): LlmOutcome<UpgradePlan> =
        invokeBinding(context, bind = { runner ->
            runner.createObject<UpgradePlan>(
                render(prompts.proposePlan(), mapOf("model" to repoModel, "goal" to goal)),
            )
        })

    fun diagnoseFailure(
        context: com.embabel.agent.api.common.OperationContext,
        build: BuildResult,
    ): LlmOutcome<BuildDiagnosis> =
        invokeBinding(context, bind = { runner ->
            runner.createObject<BuildDiagnosis>(
                render(
                    prompts.diagnoseFailure(),
                    mapOf("failedGoals" to build.failedGoals, "log" to build.log.head + "\n...\n" + build.log.tail),
                ),
            )
        })

    fun proposePatch(
        context: com.embabel.agent.api.common.OperationContext,
        diagnosis: BuildDiagnosis,
        fileContent: String,
    ): LlmOutcome<CodePatch> =
        invokeBinding(context, bind = { runner ->
            runner.createObject<CodePatch>(
                render(prompts.proposePatch(), mapOf("diagnosis" to diagnosis, "content" to fileContent)),
            )
        })

    /** Prompt rendering: template values serialized deterministically. */
    fun render(
        template: String,
        vars: Map<String, Any>,
    ): String {
        var out = template
        for ((k, v) in vars) {
            out = out.replace("{{$k}}", ProposalJson.mapper.writeValueAsString(v))
        }
        return out
    }

    private fun <T : Any> invokeBinding(
        context: com.embabel.agent.api.common.OperationContext,
        bind: (com.embabel.agent.api.common.PromptRunner) -> T,
    ): LlmOutcome<T> {
        val ai = context.ai()
        return try {
            val value = call.invoke(call = { _ -> bind(ai.withLlmByRole("planner")) })
            LlmOutcome.Accepted(value, call.attempts.toList())
        } catch (e: Exception) {
            LlmOutcome.Rejected(
                ValidationRejection(
                    checkName = "L0:binding",
                    reason = "llm output failed typed binding after ${call.attempts.size} attempt(s): ${e.message}",
                    offendingContent = "",
                ),
                call.attempts.toList(),
            )
        }
    }
}
