package com.renovator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RenovatorApplicationTests {

    @LocalServerPort
    var port: Int = 0

    @Test
    fun `context loads`() {
        // The empty test body is intentional: @SpringBootTest itself is the assertion.
    }

    @Test
    fun `actuator health endpoint is up`() {
        // DRIFT (absorbed per §13.3, recorded in the phase-0 report): Spring Boot 4 removed
        // TestRestTemplate (not present in spring-boot-test-4.1.1); Spring Framework 7 offers
        // RestTestClient (org.springframework.test.web.servlet.client) as the replacement.
        // This smoke assertion uses spring-web's RestClient — stable, main-API, zero new surface.
        val client = RestClient.create()
        val response: ResponseEntity<String> = client.get()
            .uri("http://localhost:$port/actuator/health")
            .retrieve()
            .toEntity(String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
        // show-details: always is set for the demos, so assert within the JSON rather than
        // pinning the exact (details-dependent) body shape.
        assertTrue(response.body!!.contains(""""status":"UP""""))
    }
}
