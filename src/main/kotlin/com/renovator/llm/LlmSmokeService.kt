package com.renovator.llm

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.createObject
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.stereotype.Service

/**
 * Minimal real-LLM round trip (Task 0.5): the ONLY production code riding a live
 * provider in this phase. Uses the same typed-binding path every LLM action will
 * use (D6, C-1) so the smoke proves the whole binding chain, not just connectivity.
 */
data class PingResponse(
    @JsonProperty(required = true)
    val answer: String,
)

@Service
class LlmSmokeService(
    private val ai: Ai,
) {
    /**
     * `withDefaultLlm()` resolves `embabel.models.default-llm` (set from
     * `renovator.llm.model` by LlmEnvironmentPostProcessor), so this one line's
     * behavior changes with LLM_PROVIDER and nothing else does (D5).
     */
    fun ping(): PingResponse {
        val prompt = "Reply with JSON: {\"answer\": \"pong\"}. Do not add anything else."
        return ai.withDefaultLlm().createObject<PingResponse>(prompt)
    }
}
