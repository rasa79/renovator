package com.renovator.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * Stark deserialization for proposal payloads (D6 / Layer 0 belt-and-braces).
 *
 * Boot's default ObjectMapper leniently ignores unknown JSON properties; the
 * proposal path must NOT be lenient — an LLM emitting `{"from": "3.13.0", ...}`
 * instead of `fromVersion` must fail deserialization, never silently drop the
 * field. This dedicated mapper is strict on unknown properties and used by the
 * proposal round-trip tests and by any boundary that reads raw proposal JSON.
 *
 * Note (Jackson 3, verified 2026-08-30): Boot 4 uses `tools.jackson.*`
 * (databind/core), and Jackson 3 deliberately *retains the 2.x annotations
 * artifact* (`com.fasterxml.jackson.annotation.*`) — documented in the
 * jackson-databind 3.1.5 pom ("3.x retains dep to annotations 2.x"). So the
 * proposal types use `com.fasterxml.jackson.annotation.*` for annotations and
 * `tools.jackson.*` for the machinery — mixed on purpose, not by accident.
 */
@Configuration(proxyBeanMethods = false)
class JacksonConfig {
    @Bean
    fun proposalObjectMapper(): JsonMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            // Strict by default in Jackson 3; stated explicitly so the contract is
            // visible here, not left to the library's default.
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
}
