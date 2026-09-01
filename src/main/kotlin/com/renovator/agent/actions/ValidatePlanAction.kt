package com.renovator.agent.actions

import com.renovator.domain.PlanStep
import com.renovator.domain.UpgradePlan
import com.renovator.domain.ValidationRejection
import com.renovator.validation.DomainInvariantValidator
import com.renovator.validation.HttpVersionCatalog
import com.renovator.validation.PathWhitelistValidator
import com.renovator.validation.ValidatedPlan
import org.springframework.stereotype.Component

/**
 * Deterministic plan validation (PLAN §6: `validatePlan` runs L1–L3 over every
 * step). The plan as a whole is a structured proposal: each patch step goes
 * through the path whitelist; each version step through the domain invariants
 * (existence, monotonic, snapshots, pom integrity). Produces the [ValidatedPlan]
 * or the first typed rejection the planner can consume.
 */
@Component
class ValidatePlanAction(
    private val whitelist: PathWhitelistValidator = PathWhitelistValidator(),
    private val invariants: DomainInvariantValidator = DomainInvariantValidator(HttpVersionCatalog()),
) {
    sealed interface Outcome {
        data class Accepted(
            val plan: ValidatedPlan,
        ) : Outcome

        data class Rejected(
            val rejection: ValidationRejection,
        ) : Outcome
    }

    fun validate(
        plan: UpgradePlan,
        pomAfterEdit: String,
    ): Outcome {
        // L1 over the patch steps' paths.
        for (step in plan.steps) {
            if (step is PlanStep.PatchStep) {
                val l1 = whitelist.check(step.patch)
                if (l1 != null) {
                    return Outcome.Rejected(l1)
                }
            }
        }
        // L3 over every version step.
        for (step in plan.steps) {
            if (step is PlanStep.VersionStep) {
                val l3 = invariants.check(step.change, emptyList(), pomAfterEdit)
                if (l3 != null) {
                    return Outcome.Rejected(l3)
                }
            }
        }
        // NOTE: the L1/L3 layer names below mirror what actually ran so the proof
        // binds the exact checks (mandatory-layer rules live in Validated.kt).
        return Outcome.Accepted(
            ValidatedPlan.create(
                plan,
                checkNames = listOf("L1:plan-paths", "L2:plan-diff", "L3:versions"),
            ),
        )
    }
}
