package com.renovator.domain

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeInfo.As
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id

// LEARN[005] Kotlin for a Java engineer: the proposal types are the contract
// Why this way: every value the LLM produces crosses this file's types. Kotlin's
//   data class is a Java record with less ceremony (equals/hashCode/toString/copy
//   for free), and `val` means the blackboard only ever holds immutable values —
//   an LLM hallucination can corrupt a constructor argument, but it cannot mutate
//   an object an index later: the audit trail stays trustworthy by construction.
// Good sides: the sealed hierarchy makes "keep the artifact" and "two hops" a type
//   you can `when` over exhaustively (the compiler forces the check — a review-
//   comment never does), and null-safety turns optional JSON fields into `String?`
//   that the binder rejects explicitly. Compare Java: Optional<...> leaking into
//   models, and a `default:` switch case that silently swallows a new subtype.
// Drawbacks: Kotlin's `internal` is module-wide, not package-wide — a same-module
//   caller can construct most of these; and sealed hierarchies plus Jackson need
//   explicit type-tag annotations, which is noise a record hierarchy in Java would
//   also need. Reflection can and will bypass everything (that is KL-07's honest
//   caveat); the boundary we defend is the LLM/planner path, not in-process code.
// Concept: think "DTOs that are also the state machine": the types say WHAT can be
//   said, validation layers decide WHAT is safe, and the executor accepts only
//   what validation signed (see validation/Validated.kt). Write the types as if
//   an adversarial LLM were their only caller — because in production it is.
// See also: PLAN §5, PLAN D6, domain/Results.kt, validation/Validated.kt
@JsonTypeInfo(use = Id.NAME, include = As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = Constraint.NoSnapshots::class, name = "NoSnapshots"),
    JsonSubTypes.Type(value = Constraint.MaxHops::class, name = "MaxHops"),
    JsonSubTypes.Type(value = Constraint.MustKeepArtifact::class, name = "MustKeepArtifact"),
)
sealed interface Constraint {
    data object NoSnapshots : Constraint

    data class MaxHops(
        val n: Int,
    ) : Constraint

    data class MustKeepArtifact(
        val groupId: String,
        val artifactId: String,
    ) : Constraint
}

data class DependencyTarget(
    val groupId: String,
    val artifactId: String,
    val fromVersion: String,
    val toVersion: String,
) {
    init {
        require(groupId.isNotBlank()) { "DependencyTarget.groupId must not be blank" }
        require(artifactId.isNotBlank()) { "DependencyTarget.artifactId must not be blank" }
        require(fromVersion.isNotBlank()) { "DependencyTarget.fromVersion must not be blank" }
        require(toVersion.isNotBlank()) { "DependencyTarget.toVersion must not be blank" }
    }
}

data class UpgradeGoal(
    val targets: List<DependencyTarget>,
    val constraints: List<Constraint> = emptyList(),
) {
    init {
        require(targets.isNotEmpty()) { "UpgradeGoal needs at least one target" }
    }
}

enum class ChangeScope {
    DIRECT,
    MANAGEMENT,
}

data class VersionChange(
    val groupId: String,
    val artifactId: String,
    val fromVersion: String,
    val toVersion: String,
    val scope: ChangeScope,
) {
    init {
        require(groupId.isNotBlank()) { "VersionChange.groupId must not be blank" }
        require(artifactId.isNotBlank()) { "VersionChange.artifactId must not be blank" }
        require(fromVersion.isNotBlank()) { "VersionChange.fromVersion must not be blank" }
        require(toVersion.isNotBlank()) { "VersionChange.toVersion must not be blank" }
    }
}

data class CodePatch(
    val filePath: String,
    val unifiedDiff: String,
    val justification: String,
) {
    init {
        require(filePath.isNotBlank()) { "CodePatch.filePath must not be blank" }
        require(unifiedDiff.isNotBlank()) { "CodePatch.unifiedDiff must not be blank" }
    }
}

/** Planner-facing diagnosis of a failed build. Advisory only (KL-04): correctness
 *  is asserted by build/test outcomes, never by this model's confidence. */
data class BuildDiagnosis(
    val failedGoals: List<String>,
    val rootCauses: List<RootCause>,
    val suggestedActions: List<ActionHint>,
)

data class RootCause(
    val symbolOrArtifact: String,
    val explanation: String,
)

enum class HintKind {
    PIN_TRANSITIVE,
    MULTI_HOP,
    PATCH_CODE,
    ESCALATE,
}

data class ActionHint(
    val kind: HintKind,
    val detail: String,
)

@JsonTypeInfo(use = Id.NAME, include = As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = PlanStep.VersionStep::class, name = "VersionStep"),
    JsonSubTypes.Type(value = PlanStep.PatchStep::class, name = "PatchStep"),
)
sealed interface PlanStep {
    data class VersionStep(
        val change: VersionChange,
    ) : PlanStep

    data class PatchStep(
        val patch: CodePatch,
    ) : PlanStep
}

data class UpgradePlan(
    val steps: List<PlanStep>,
    val rationale: String,
) {
    init {
        require(steps.isNotEmpty()) { "UpgradePlan needs at least one step" }
    }
}
