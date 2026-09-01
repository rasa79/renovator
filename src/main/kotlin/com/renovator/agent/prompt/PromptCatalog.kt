package com.renovator.agent.prompt

import org.springframework.core.io.ClassPathResource

/**
 * Prompts live ONLY here (PLAN §10.5: one versioned location under
 * src/main/resources/prompts; the protocol checker hard-fails prompt-shaped
 * literals elsewhere from Phase 3 on). Editing a prompt is a commit of its own.
 */
class PromptCatalog {
    private fun load(name: String): String = ClassPathResource("prompts/$name.st").inputStream.bufferedReader().readText()

    fun proposePlan(): String = load("propose_plan")

    fun diagnoseFailure(): String = load("diagnose_failure")

    fun proposePatch(): String = load("propose_patch")
}
