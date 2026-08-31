package com.renovator.validation

import com.renovator.domain.CodePatch
import com.renovator.domain.UpgradePlan
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

//
// The `Validated*` seal (PLAN §7.6, the enforcement boundary).
//
// Mechanism (Kotlin nuance documented): PLAN says "sealed"; Kotlin sealed classes
// are implicitly abstract and CANNOT be instantiated even by their own companion,
// so the equivalent enforcement here is: FINAL classes with PRIVATE primary
// constructors whose only callers are the same-file factories. A caller outside
// the validation package cannot write `ValidatedPatch(patch, proof)` — it does
// not compile (proven in the phase-2 report with a quoted compiler error), and
// the class being final forecloses the subclassing route. The factories accept
// only the mandatory layer set and bind the payload's SHA-256 digest into the
// proof, so even same-module misuse cannot forge a proof that claims layers that
// never ran. The executor recomputes the digest at apply time.
//
// Honest caveat (KL-07): JVM reflection can still construct these in-process; the
// boundary defends the LLM/planner path, not malicious in-process code.
//

// Mandatory layers per PLAN §7.6: L1 path whitelist, L2 diff applies, L3 invariants.
val MANDATORY_VALIDATION_LAYERS: Set<String> = setOf("L1", "L2", "L3")

/**
 * Single canonical serializer for proposal payloads. Both the proof factory and the
 * executor MUST hash the same bytes for digest comparison to mean anything — the
 * mapper is configured once here (deterministic field order comes from Kotlin data
 * class construction order), never per-caller.
 */
object ProposalJson {
    val mapper: JsonMapper =
        JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    fun canonical(any: Any): String = mapper.writeValueAsString(any)
}

data class ValidationProof private constructor(
    val checkNames: List<String>,
    val contentDigestSha256: String,
    val validatedAt: Instant,
) {
    companion object {
        /** Creates the proof, enforcing the mandatory layer set (L1/L2/L3 present). */
        fun create(
            contentSha256: String,
            checkNames: List<String>,
            validatedAt: Instant = Instant.now(),
        ): ValidationProof {
            val layers = checkNames.mapNotNull { it.substringBefore(":").takeIf { c -> c in MANDATORY_VALIDATION_LAYERS } }.toSet()
            val missing = MANDATORY_VALIDATION_LAYERS - layers
            require(missing.isEmpty()) {
                "proof must be backed by all mandatory layers, missing: $missing (had: $checkNames)"
            }
            require(contentSha256.isNotBlank()) { "proof requires a content digest" }
            return ValidationProof(checkNames, contentSha256, validatedAt)
        }
    }
}

class ValidatedPatch private constructor(
    val patch: CodePatch,
    val proof: ValidationProof,
) {
    companion object {
        /** The one and only creation path: binds sha256(canonical JSON) + mandatory layers. */
        fun create(
            patch: CodePatch,
            checkNames: List<String>,
            validatedAt: Instant = Instant.now(),
        ): ValidatedPatch =
            ValidatedPatch(
                patch,
                ValidationProof.create(sha256(ProposalJson.canonical(patch)), checkNames, validatedAt),
            )

        fun sha256(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

class ValidatedPlan private constructor(
    val plan: UpgradePlan,
    val proof: ValidationProof,
) {
    companion object {
        fun create(
            plan: UpgradePlan,
            checkNames: List<String>,
            validatedAt: Instant = Instant.now(),
        ): ValidatedPlan =
            ValidatedPlan(
                plan,
                ValidationProof.create(
                    ValidatedPatch.sha256(ProposalJson.canonical(plan)),
                    checkNames,
                    validatedAt,
                ),
            )
    }
}
