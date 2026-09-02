package com.renovator.api

import com.renovator.domain.HumanDecision
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Decision surface (PLAN Task 5.3): the pending-decision payload renders the
 * gate's question (and the blocker's attempts), and the submission maps to the
 * service path. Typed 422 when nothing is pending.
 */
@WebMvcTest(DecisionController::class)
class DecisionControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var runs: RunService

    @Test
    fun `pending decision renders the gate payload`() {
        Mockito.`when`(runs.pendingDecision("run-1")).thenReturn(
            PendingDecision(
                runId = "run-1",
                kind = "approval",
                question = "approval required (commit-candidate): single bump",
                approved = null,
                attempts = 0,
            ),
        )
        mockMvc
            .perform(get("/api/runs/run-1/pending-decision"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.kind").value("approval"))
            .andExpect(jsonPath("$.question").value("approval required (commit-candidate): single bump"))
    }

    @Test
    fun `no pending decision is a typed 422`() {
        Mockito.`when`(runs.pendingDecision("run-2")).thenReturn(null)
        mockMvc
            .perform(get("/api/runs/run-2/pending-decision"))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("invalid-request"))
    }

    @Test
    fun `submitting a decision maps to the service path`() {
        Mockito
            .`when`(
                runs.submitDecision(
                    org.mockito.kotlin.eq("run-1"),
                    org.mockito.kotlin.any(),
                ),
            ).thenReturn(RunStatus(runId = "run-1", status = "WAITING", stage = "GatePending", attempts = 1))

        mockMvc
            .perform(
                post("/api/runs/run-1/decisions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"approved": true, "comment": "go"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.stage").value("GatePending"))

        Mockito.verify(runs).submitDecision(
            org.mockito.kotlin.eq("run-1"),
            org.mockito.kotlin.any<HumanDecision>(),
        )
    }
}
