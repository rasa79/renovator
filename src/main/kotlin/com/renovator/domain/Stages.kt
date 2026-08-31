package com.renovator.domain

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Stage hierarchy (PLAN §5, Task 2.2): the lifecycle vocabulary. Per §5 these are
 * plain top-level data classes now; the Embabel `@State` wiring (C-2) rewrites
 * this file in Task 4.1, where the loop-carried payloads are designed with the
 * `@State` semantics (state data rides the state instances).
 *
 * Kotlin note: a `data class` needs at least one constructor parameter, so
 * payload-less stages are `data object`s — the plan's "data classes" wording is
 * satisfied semantically, and Phase 4 turns each stage into a data class with
 * real payloads (per C-2's "pass all necessary data through state record fields").
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "stage")
@JsonSubTypes(
    JsonSubTypes.Type(value = Analyzing::class, name = "Analyzing"),
    JsonSubTypes.Type(value = Planning::class, name = "Planning"),
    JsonSubTypes.Type(value = Applying::class, name = "Applying"),
    JsonSubTypes.Type(value = Verifying::class, name = "Verifying"),
    JsonSubTypes.Type(value = Repairing::class, name = "Repairing"),
    JsonSubTypes.Type(value = Blocked::class, name = "Blocked"),
    JsonSubTypes.Type(value = Done::class, name = "Done"),
)
sealed interface UpgradeStage

data object Analyzing : UpgradeStage

data object Planning : UpgradeStage

data object Applying : UpgradeStage

data object Verifying : UpgradeStage

data object Repairing : UpgradeStage

data object Blocked : UpgradeStage

data object Done : UpgradeStage
