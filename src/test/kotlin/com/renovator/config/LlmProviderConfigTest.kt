package com.renovator.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Proves the provider->Embabel mapping (D5): the SAME mapped keys serve both modes,
 * and the agent code never mentions a provider (acceptance grep confirms that only
 * this package contains `provider` logic).
 */
class LlmProviderConfigTest {
    @Test
    fun `ollama provider yields openai-custom base url and default-llm without code change`() {
        val bindings = LlmProviderConfig.embabelBindings("ollama", "llama3.1", null, null)
        assertEquals(
            "http://localhost:11434/v1",
            bindings["embabel.agent.platform.models.openai.custom.base-url"],
        )
        assertEquals("llama3.1", bindings["embabel.agent.platform.models.openai.custom.models"])
        assertEquals("llama3.1", bindings["embabel.models.default-llm"])
        // Ollama mode never touches the plain OpenAI endpoint keys.
        assertFalse(bindings.containsKey("embabel.agent.platform.models.openai.base-url"))
    }

    @Test
    fun `cloud provider yields OPENAI base url path`() {
        val bindings =
            LlmProviderConfig.embabelBindings(
                "cloud",
                "gpt-4.1-mini",
                "https://api.mycorp.example",
                "sk-test",
            )
        assertEquals("https://api.mycorp.example", bindings["embabel.agent.platform.models.openai.base-url"])
        assertEquals("sk-test", bindings["embabel.agent.platform.models.openai.api-key"])
        assertEquals("gpt-4.1-mini", bindings["embabel.models.default-llm"])
        assertFalse(bindings.containsKey("embabel.agent.platform.models.openai.custom.base-url"))

        // No base-url configured -> key ABSENT (null means "OpenAI default location";
        // a blank string would NPE in openai-java — see verification-log row C-8).
        val defaults = LlmProviderConfig.embabelBindings("cloud", "gpt-4.1-mini", null, null)
        assertFalse(defaults.containsKey("embabel.agent.platform.models.openai.base-url"))
        assertEquals("gpt-4.1-mini", defaults["embabel.models.default-llm"])
    }
}
