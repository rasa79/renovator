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

/** A typed validation failure, placed on the blackboard to inform replanning. */
data class ValidationRejection(
    val checkName: String,
    val reason: String,
    val offendingContent: String,
)

data class CompileCheckResult(
    val success: Boolean,
    val errors: List<CompileError>,
    // REFINEMENT (phase-2 report): distinguishes "not run" (dry-run-compile=off)
    // from "ran and passed"; PLAN §5 lists success+errors only.
    val skipped: Boolean = false,
)

data class TestFailure(
    val name: String,
    val message: String,
)

data class TestResult(
    val passed: Int,
    val failed: Int,
    val failures: List<TestFailure>,
)

data class UpgradeBlocker(
    val summary: String,
    val attempts: List<AttemptRecord>,
    val humanQuestion: String,
) {
    init {
        require(attempts.isNotEmpty()) { "UpgradeBlocker needs a non-empty attempt history" }
        require(humanQuestion.isNotBlank()) { "UpgradeBlocker needs a human question" }
    }
}

data class AttemptRecord(
    val planRationale: String,
    val rejectedAt: String?,
    val buildFailedGoals: List<String>,
    val validationRejections: List<ValidationRejection>,
)

data class UpgradeComplete(
    val appliedSteps: List<PlanStep>,
    val finalBuild: BuildResult,
    val durationMs: Long,
)

data class HumanDecision(
    val approved: Boolean,
    val comment: String,
)

/**
 * Single-output composition for `runBuild` (PLAN §6 shows "BuildResult +
 * TestResult" — one action yields one blackboard object, so the judge's verdict
 * and the parsed test totals travel together; documented in the phase-3 report).
 */
data class WorkspaceVerdict(
    val build: BuildResult,
    val tests: TestResult,
)
