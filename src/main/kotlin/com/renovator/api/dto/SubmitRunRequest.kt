package com.renovator.api

import com.renovator.domain.UpgradeGoal

/** POST /api/runs body (PLAN Task 5.1). */
data class SubmitRunRequest(
    val repoPath: String,
    val goal: UpgradeGoal,
)

/** 202 response: the accepted run's id. */
data class SubmitRunResponse(
    val runId: String,
)

/** Typed error body (PLAN Task 5.1 acceptance: typed errors, not stack traces). */
data class ApiError(
    val code: String,
    val message: String,
)
