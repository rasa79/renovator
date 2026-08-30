package com.renovator.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class RenovatorPropertiesTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(BindConfig::class.java)

    @Configuration
    @EnableConfigurationProperties(RenovatorProperties::class)
    class BindConfig

    @Test
    fun `binds defaults with no env vars`() {
        runner.run { ctx ->
            val p = ctx.getBean(RenovatorProperties::class.java)
            assertEquals("cloud", p.llm.provider)
            assertEquals("gpt-4.1-mini", p.llm.model)
            assertEquals("planner", p.llm.plannerRole)
            assertEquals("maven:3.9.11-eclipse-temurin-25", p.sandbox.image)
            assertEquals(120, p.sandbox.timeoutSeconds)
            assertEquals(2048, p.sandbox.memoryMb)
            assertEquals(2, p.sandbox.cpus)
            assertEquals("renovator-m2-cache", p.sandbox.cacheVolume)
            assertEquals(
                listOf("pom.xml", "src/main/java/**", "src/main/kotlin/**", "src/test/**"),
                p.validation.allowedPaths,
            )
            assertEquals(listOf(".git/**", "**/*.sh", "**/secrets/**", "**/.env*"), p.validation.forbiddenPaths)
            assertEquals(listOf("https://repo1.maven.org/maven2"), p.validation.allowedRepositories)
            assertEquals(false, p.validation.allowSnapshots)
            assertEquals(RenovatorProperties.DryRunCompileMode.ON_COMMIT_CANDIDATE, p.validation.dryRunCompile)
            assertEquals(false, p.approvals.plan)
            assertEquals(false, p.approvals.commitCandidate)
            assertEquals(25, p.budget.maxActions)
        }
    }

    @Test
    fun `rejects sandbox timeout below 10 seconds`() {
        runner.withPropertyValues("renovator.sandbox.timeout-seconds=5").run { ctx ->
            val failure = ctx.startupFailure
            assertNotNull(failure, "a timeout of 5s must fail binding")
            // Binding wraps the require() failure (ConfigurationPropertiesBindException /
            // BeanCreationException); the reason lives somewhere in the cause chain.
            assertTrue(
                causeChain(failure).contains("timeout-seconds"),
                "failure should name the property: $failure",
            )
        }
    }

    @Test
    fun `rejects unknown provider value`() {
        runner.withPropertyValues("renovator.llm.provider=azure").run { ctx ->
            val failure = ctx.startupFailure
            assertNotNull(failure, "provider 'azure' must fail binding")
            assertTrue(
                causeChain(failure).contains("provider"),
                "failure should name the provider: $failure",
            )
        }
    }

    private fun causeChain(t: Throwable?): String = generateSequence(t) { it.cause }.joinToString(" | ") { it.message ?: "" }
}
