package com.renovator.api

import com.renovator.config.RenovatorProperties
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeGoal
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.nio.file.Files
import java.nio.file.Path

/**
 * Control API (PLAN Task 5.1): submit a goal (202 + run id), read run status and
 * the typed trajectory. Errors are typed [ApiError]s (never stack traces);
 * KL-01's single-run conflict is 409 via [ConflictException], KL-03's repo
 * validation is 422.
 */
@RestController
class RunController(
    private val runs: RunService,
    private val properties: RenovatorProperties,
) {
    @PostMapping("/api/runs")
    fun submit(
        @RequestBody request: SubmitRunRequest,
    ): ResponseEntity<SubmitRunResponse> {
        val repoPath = validateRepoPath(request.repoPath)
        val runId =
            runs.submit(
                goal = request.goal,
                runRequest = RunRequest(repoPath = repoPath, goal = request.goal),
            )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(SubmitRunResponse(runId))
    }

    @GetMapping("/api/runs/{id}")
    fun status(
        @PathVariable id: String,
    ): RunStatus {
        ensureKnown(id)
        return runs.status(id)
    }

    @GetMapping("/api/runs/{id}/trajectory")
    fun trajectory(
        @PathVariable id: String,
        @RequestParam(name = "type", required = false) eventType: String?,
    ): Map<String, Any> {
        ensureKnown(id)
        return mapOf("runId" to id, "events" to runs.trajectory(id, eventType))
    }

    /** KL-03: exists, is a directory under an allowed root, and contains pom.xml. */
    fun validateRepoPath(raw: String): Path {
        val path = Path.of(raw).toAbsolutePath().normalize()
        require(Files.exists(path) && Files.isDirectory(path)) { "repo path does not exist or is not a directory: $raw" }
        val roots =
            properties.api.allowedRoots
                .map { Path.of(it).toAbsolutePath().normalize() }
                .ifEmpty { listOf(Path.of(".").toAbsolutePath().normalize()) }
        require(roots.any { path.startsWith(it) }) { "repo path '$raw' is outside the allowed roots (${roots.joinToString()})" }
        require(Files.exists(path.resolve("pom.xml"))) { "repo path '$raw' does not contain pom.xml (Maven-only scope, KL-03)" }
        return path
    }

    private fun ensureKnown(runId: String) {
        if (runs.trajectory(runId).isEmpty()) {
            throw IllegalArgumentException("no run with id '$runId'")
        }
    }
}

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ConflictException::class)
    fun conflict(e: ConflictException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError("conflict", e.message ?: "conflict"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiError("invalid-request", e.message ?: "invalid request"))
}
