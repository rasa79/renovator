package com.renovator.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * All Renovator runtime configuration, bound from `renovator.*` (see application.yml).
 * Nested data classes with sane defaults: the walking skeleton boots with zero env vars.
 *
 * Validation is enforced through `require` in the primary constructor: constructor-based
 * binding runs the checks while the context is being created, so an invalid value fails
 * fast with a named reason instead of producing a half-configured bean (RenovatorPropertiesTest).
 */
@ConfigurationProperties(prefix = "renovator")
data class RenovatorProperties(
    val llm: Llm = Llm(),
    val sandbox: Sandbox = Sandbox(),
    val validation: Validation = Validation(),
    val approvals: Approvals = Approvals(),
    val budget: Budget = Budget(),
) {
    /** Dual-provider LLM settings (D5). [provider] is `cloud` or `ollama`. */
    data class Llm(
        val provider: String = "cloud",
        val baseUrl: String? = null,
        val apiKey: String? = null,
        val model: String = "gpt-4.1-mini",
        val plannerRole: String = "planner",
    )

    /** Sandbox build runner settings (D7); defaults match PLAN §8.5. */
    data class Sandbox(
        val image: String = "maven:3.9.11-eclipse-temurin-25",
        val timeoutSeconds: Int = 120,
        val memoryMb: Int = 2048,
        val cpus: Int = 2,
        val cacheVolume: String = "renovator-m2-cache",
    )

    /** Validation pipeline config (D8; §7 layers 1–3 and the L4 toggle). */
    data class Validation(
        val allowedPaths: List<String> =
            listOf(
                "pom.xml",
                "src/main/java/**",
                "src/main/kotlin/**",
                "src/test/**",
            ),
        val forbiddenPaths: List<String> =
            listOf(
                ".git/**",
                "**/*.sh",
                "**/secrets/**",
                "**/.env*",
            ),
        val allowedRepositories: List<String> = listOf("https://repo1.maven.org/maven2"),
        val allowSnapshots: Boolean = false,
        val dryRunCompile: DryRunCompileMode = DryRunCompileMode.ON_COMMIT_CANDIDATE,
    )

    enum class DryRunCompileMode {
        ALWAYS,
        ON_COMMIT_CANDIDATE,
        OFF,
    }

    /** HITL approval gates (D11); both are disarmed by default so the Phase 3–4
     *  mock flows reach UpgradeComplete without human input (gates arm in Phase 5). */
    data class Approvals(
        val plan: Boolean = false,
        val commitCandidate: Boolean = false,
    )

    /** Planner bounds (C-7; R-4) — default 25 per PLAN §6/§10.2. */
    data class Budget(
        val maxActions: Int = 25,
    )

    init {
        require(llm.provider == "cloud" || llm.provider == "ollama") {
            "renovator.llm.provider must be 'cloud' or 'ollama', but was '$llm.provider'"
        }
        require(sandbox.timeoutSeconds >= 10) {
            "renovator.sandbox.timeout-seconds must be >= 10 (a shorter timeout cannot be " +
                "distinguished from a failed container start), but was ${sandbox.timeoutSeconds}"
        }
        require(sandbox.cpus >= 1) {
            "renovator.sandbox.cpus must be >= 1, but was ${sandbox.cpus}"
        }
        require(budget.maxActions >= 1) {
            "renovator.budget.max-actions must be >= 1, but was ${budget.maxActions}"
        }
    }
}
