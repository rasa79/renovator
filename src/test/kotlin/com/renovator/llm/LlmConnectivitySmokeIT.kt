package com.renovator.llm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Live-provider smoke (D5/KL-06): the SAME test runs against cloud or Ollama —
 * only the environment differs. Never runs in a default build; opt in with
 * `LLM_SMOKE=1` plus the provider env vars, and run under -Pllm-it (the *IT
 * pattern keeps it out of ./mvnw verify; see §10.5).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LLM_SMOKE", matches = "1")
class LlmConnectivitySmokeIT {
    @Autowired
    lateinit var smoke: LlmSmokeService

    @Test
    fun `returns a bound PingResponse from the configured provider`() {
        val response = smoke.ping()
        println("SMOKE bound answer=[${response.answer}]")
        assertFalse(response.answer.isBlank(), "the provider must return a non-blank bound answer")
        assertTrue(response.answer.lowercase().contains("pong"), "answer should say pong, was: ${response.answer}")
    }
}
