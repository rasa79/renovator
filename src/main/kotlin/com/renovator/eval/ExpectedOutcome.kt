package com.renovator.eval

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path

/**
 * The eval dataset schema (PLAN §8, D13): one `expected-outcome.yml` per fixture.
 * The four fixture files ARE the dataset — the mock eval (Phase 6) drives each
 * fixture with its canned LLM responses and compares the run against this shape.
 */
data class ExpectedOutcome(
    val fixture: String,
    val goal: Goal,
    val expectedTerminalState: ExpectedTerminalState,
    val mustVisitStages: List<UpgradeStageName>,
    val mustNotVisitStages: List<UpgradeStageName> = emptyList(),
    val maxAttempts: Int,
    val requiredArtifacts: List<String> = emptyList(),
    val notes: String = "",
) {
    data class Goal(
        val targets: List<DependencyTarget>,
        val constraints: List<Constraint> = emptyList(),
    )

    data class DependencyTarget(
        val groupId: String,
        val artifactId: String,
        val fromVersion: String,
        val toVersion: String,
    )

    enum class Constraint {
        NoSnapshots,
        MaxHops,
        MustKeepArtifact,
    }

    /** Mirrors the UpgradeStage hierarchy (Phase 4, @State). */
    enum class UpgradeStageName {
        Analyzing,
        Planning,
        Applying,
        Verifying,
        Repairing,
        Blocked,
        Done,
    }

    enum class ExpectedTerminalState {
        UpgradeComplete,
        UpgradeBlocker,
    }

    init {
        require(maxAttempts >= 1) {
            "expected-outcome for '$fixture': maxAttempts must be >= 1, was $maxAttempts"
        }
        require(fixture.isNotBlank()) { "expected-outcome: fixture must not be blank" }
        require(goal.targets.isNotEmpty()) { "expected-outcome for '$fixture': goal needs >= 1 target" }
    }
}

/**
 * Loads expected-outcome.yml files. Instantiation is strict: unknown enum values or
 * an out-of-range maxAttempts fail here (with the Jackson error), never during a run.
 */
object ExpectedOutcomeLoader {
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    fun load(path: Path): ExpectedOutcome {
        require(Files.exists(path)) { "expected-outcome file not found: $path" }
        return mapper.readValue(path.toFile(), ExpectedOutcome::class.java)
    }

    /** All fixtures under [fixturesDir] that carry an expected-outcome.yml. */
    fun loadAll(fixturesDir: Path = Path.of("fixtures")): List<ExpectedOutcome> {
        val dirs =
            Files
                .list(fixturesDir)
                .use { stream ->
                    stream
                        .filter { Files.isDirectory(it) && Files.exists(it.resolve("expected-outcome.yml")) }
                        .sorted()
                        .toList()
                }
        return dirs.map { load(it.resolve("expected-outcome.yml")) }
    }
}
