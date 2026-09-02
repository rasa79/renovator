package com.renovator.api

import com.renovator.config.RenovatorProperties
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path

/**
 * Control API (PLAN Task 5.1): the 202 contract, KL-03's typed 422s, and KL-01's
 * 409 on a second concurrent submission — the promise the KL-01 ledger row makes
 * is proven here with the real controller + real validation (service mocked).
 */
@WebMvcTest(
    RunController::class,
    properties = ["renovator.api.allowed-roots=."],
)
@EnableConfigurationProperties(RenovatorProperties::class)
class RunControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var runs: RunService

    private fun validBody() =
        """
        {
          "repoPath": "fixtures/fixture-clean",
          "goal": {"targets": [
            {"groupId": "org.apache.commons", "artifactId": "commons-lang3",
             "fromVersion": "3.12.0", "toVersion": "3.14.0"}
          ]}
        }
        """.trimIndent()

    @Test
    fun `submitting a valid goal returns 202 and a run id`() {
        Mockito
            .`when`(
                runs.submit(
                    any(),
                    any(),
                    anyOrNull(),
                ),
            ).thenReturn("run-abc123")

        mockMvc
            .perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content(validBody()))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.runId").value("run-abc123"))
    }

    @Test
    fun `rejects a repo path outside allowed roots with 422`() {
        mockMvc
            .perform(
                post("/api/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validBody().replace("fixtures/fixture-clean", "/etc")),
            ).andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("invalid-request"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("outside the allowed roots")))
    }

    @Test
    fun `rejects a non-Maven target with 422`() {
        val noPom =
            java.nio.file.Files
                .createTempDirectory(Path.of("target"), "renovator-nopom")
        mockMvc
            .perform(
                post("/api/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validBody().replace("fixtures/fixture-clean", noPom.toString())),
            ).andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("invalid-request"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("pom.xml")))
    }

    @Test
    fun `second concurrent submission returns 409`() {
        // KL-01: the single-run gate is a 409, verbatim — the service refuses,
        // the controller maps the typed ConflictException.
        Mockito
            .`when`(
                runs.submit(
                    any(),
                    any(),
                    anyOrNull(),
                ),
            ).thenThrow(ConflictException(null, "a run is already active (single-run enforcement, KL-01)"))

        mockMvc
            .perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content(validBody()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("conflict"))
            .andExpect(jsonPath("$.message").value("a run is already active (single-run enforcement, KL-01)"))
    }

    @Test
    fun `unknown run id is a typed 422, not a stack trace`() {
        Mockito.`when`(runs.trajectory("missing", null)).thenReturn(emptyList())
        mockMvc
            .perform(get("/api/runs/missing"))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("invalid-request"))
            .andExpect(jsonPath("$.message").value("no run with id 'missing'"))
    }
}
