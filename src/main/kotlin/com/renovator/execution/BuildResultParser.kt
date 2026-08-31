package com.renovator.execution

/**
 * Extracts typed `failedGoals` from Maven output (PLAN §8.5 / Task 1.6):
 * the `[plugin:goal]` strings of every "Failed to execute goal" line.
 * This is what the planner sees as the failure signature — a nameable,
 * deterministic signal, not a blob of log text.
 *
 * Pattern per real samples (the files under src/test/resources/buildlogs/):
 *   [ERROR] Failed to execute goal org.apache.maven.plugins:maven-enforcer-plugin:3.6.3:enforce (enforce-convergence) ...
 *   [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) ...
 */
object BuildResultParser {
    private val FAILED_GOAL = Regex("""Failed to execute goal [^:]+:([^:]+):[^:]+:([^ (]+)""")

    fun failedGoals(output: String): List<String> =
        FAILED_GOAL
            .findAll(output)
            .map { "[" + it.groupValues[1] + ":" + it.groupValues[2] + "]" }
            .distinct()
            .toList()
}
