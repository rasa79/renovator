package com.renovator.agent.llm

import com.renovator.agent.actions.LlmActions

/**
 * The single LLM channel: @State instances are constructed by the framework and
 * cannot take constructor injection, so the palette routes LLM calls through this
 * reseatable holder. Production default = the real `LlmActions` (typed binding +
 * KL-12 taxonomy); tests swap in scripted implementations (HappyPathUpgradeIT).
 */
object LlmChannel {
    @Volatile
    var actions: LlmActions = LlmActions()
}
