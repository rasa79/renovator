package com.renovator.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

// LEARN[001] One client abstraction, two providers: the OpenAI-compatible trick
// Why this way: Embabel exposes two starters for the same wire protocol — the plain
//   OpenAI starter (OPENAI_API_KEY / OPENAI_BASE_URL) and `openai-custom` for any
//   OpenAI-compatible endpoint (OPENAI_CUSTOM_*). Ollama speaks that protocol, so the
//   provider switch is a *configuration* difference, not a code difference: the agent
//   never sees "local" or "cloud" — it sees `embabel.models.default-llm` and a model name.
//   Renovator's own settings (renovator.llm.*) are the mapped, validated source of truth;
//   this class is the ONLY place whose logic mentions a provider value (the §10.5
//   "no provider-branching outside config binding" rule, enforced by grep in Task 0.4's
//   acceptance and by LlmProviderConfigTest).
// Good sides: zero code change between modes (D5); the planner, prompts, and tests are
//   provider-agnostic; a new OpenAI-compatible vendor means a new config default, not a
//   new code path; failures stay in configuration (a missing base URL is a property
//   problem, not an agent bug).
// Drawbacks: the mapping has to know Embabel's property names per starter — if Embabel
//   renames them, this file breaks (verified against v1.5.1 sources in the verification
//   log: `embabel.agent.platform.models.openai.*` vs `...openai.custom.*`); the custom
//   starter still requires a non-blank api-key even for keyless local servers, so the
//   mapping injects the placeholder "ollama" (a local server ignores it) — noted in
//   application.yml as well.
// Concept: think of it as Spring profiles done as data: one Mapper<Provider, Properties>.
//   All the branching lives in a pure function returning a property map; the
//   EnvironmentPostProcessor just applies it first (above application.yml, below real
//   env vars). An engineer who wants to understand "how do I make this talk to Ollama"
//   reads one function and one yml block.
// See also: PLAN §2 C-8, PLAN D5
@Configuration(proxyBeanMethods = false)
class LlmProviderConfig {
    companion object {
        /** WSL2-native Ollama (D15): the Windows-side `ollama serve` must bind 0.0.0.0 —
         *  the default 127.0.0.1 binding is invisible from WSL2 — and is reached via
         *  LLM_BASE_URL (default http://localhost:11434) + /v1. */
        const val DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434"

        /**
         * Pure mapping [provider] -> Embabel property values.
         *  - always: `embabel.models.default-llm` = [model]
         *  - cloud: `embabel.agent.platform.models.openai.base-url` only when a base URL
         *    is configured (null means "OpenAI default" — an empty-string placeholder
         *    NPEs in openai-java, see verification-log C-8)
         *  - ollama: `embabel.agent.platform.models.openai.custom.*` base-url/models/api-key
         */
        fun embabelBindings(
            provider: String,
            model: String,
            baseUrl: String?,
            apiKey: String?,
            plannerRole: String = "planner",
        ): Map<String, String> {
            if (provider == "ollama") {
                val base =
                    (baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_OLLAMA_BASE_URL)
                        .trimEnd('/')
                        .let { if (it.endsWith("/v1")) it else "$it/v1" }
                return mapOf(
                    "embabel.agent.platform.models.openai.custom.base-url" to base,
                    // Empty-string api-key (the ${LLM_API_KEY:} yml default) must be
                    // treated as unset: Embabel rejects blank keys for the custom
                    // starter even though a local Ollama ignores key material.
                    "embabel.agent.platform.models.openai.custom.api-key" to (apiKey?.takeIf { it.isNotBlank() } ?: "ollama"),
                    "embabel.agent.platform.models.openai.custom.models" to model,
                    "embabel.models.default-llm" to model,
                    // Role mapping (C-8): `withLlmByRole("planner")` resolves via this.
                    "embabel.models.llms.$plannerRole" to model,
                )
            }
            val out = mutableMapOf<String, String>("embabel.models.default-llm" to model)
            out["embabel.models.llms.$plannerRole"] = model
            baseUrl?.takeIf { it.isNotBlank() }?.let {
                out["embabel.agent.platform.models.openai.base-url"] = it
            }
            apiKey?.takeIf { it.isNotBlank() }?.let {
                out["embabel.agent.platform.models.openai.api-key"] = it
            }
            return out
        }
    }
}

/**
 * Applies [LlmProviderConfig.embabelBindings] to the Spring environment before any bean
 * is created: adds a [MapPropertySource] that sits above application.yml (so the mapping
 * wins over yml defaults) but below real environment variables/system properties (so an
 * explicit OPENAI_BASE_URL still wins). Registered via
 * `META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor`.
 */
class LlmEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        val provider = environment.getProperty("renovator.llm.provider", "cloud")
        val model = environment.getProperty("renovator.llm.model", "gpt-4.1-mini")
        val baseUrl = environment.getProperty("renovator.llm.base-url")
        val apiKey = environment.getProperty("renovator.llm.api-key")
        val plannerRole = environment.getProperty("renovator.llm.planner-role", "planner")
        val bindings = LlmProviderConfig.embabelBindings(provider, model, baseUrl, apiKey, plannerRole)
        if (bindings.isNotEmpty()) {
            environment.propertySources.addFirst(MapPropertySource("renovator-llm-bindings", bindings))
        }
    }
}
