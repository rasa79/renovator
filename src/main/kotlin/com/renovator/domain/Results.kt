package com.renovator.domain

import com.renovator.execution.Excerpt

/**
 * Phase-1 subset of the result types (PLAN §5, file domain/Results.kt). The sandbox
 * runner needs BuildResult in Phase 1, so this file lands now with the types the
 * deterministic judge produces; the remaining result types (ValidationRejection,
 * UpgradeBlocker, AttemptRecord, UpgradeComplete, …) are added here in Task 2.2.
 */
data class BuildResult(
    val success: Boolean,
    val failedGoals: List<String>,
    val log: Excerpt,
    val durationMs: Long,
)

/** Typed javac failure (ParseableCompilerError — Task 2.6 wires the parser). */
data class CompileError(
    val filePath: String,
    val line: Int,
    val column: Int,
    val message: String,
)
