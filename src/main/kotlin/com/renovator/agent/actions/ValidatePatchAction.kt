package com.renovator.agent.actions

import com.renovator.domain.CodePatch
import com.renovator.domain.RunRequest
import com.renovator.domain.ValidationRejection
import com.renovator.validation.DiffApplyValidator
import com.renovator.validation.PathWhitelistValidator
import com.renovator.validation.ValidatedPatch
import java.nio.file.Files

/**
 * Deterministic patch validation (PLAN §6: `validatePatch` runs L1–L2 plus the
 * patch L3 invariant — the §7.6 proof seal makes L3 mandatory for every
 * Validated*, and a code patch's L3 is the build-file/vcs scope rule in
 * DomainInvariantValidator): the path must be whitelisted, the scope invariant
 * must hold, AND the unified diff must apply cleanly to the current file content.
 * The resulting [ValidatedPatch] is the only patch type the executor accepts
 * (Task 2.7 border).
 */
object ValidatePatchAction {
    private val whitelist: PathWhitelistValidator = PathWhitelistValidator()
    private val diffs: DiffApplyValidator = DiffApplyValidator()
    private val domain = com.renovator.validation.DomainInvariantValidator(com.renovator.validation.HttpVersionCatalog())

    sealed interface Outcome {
        data class Accepted(
            val patch: ValidatedPatch,
        ) : Outcome

        data class Rejected(
            val rejection: ValidationRejection,
        ) : Outcome
    }

    fun validate(
        patch: CodePatch,
        runRequest: RunRequest,
    ): Outcome {
        val l1 = whitelist.check(patch)
        if (l1 != null) {
            return Outcome.Rejected(l1)
        }
        val l3 = domain.check(patch)
        if (l3 != null) {
            return Outcome.Rejected(l3)
        }
        val target = runRequest.repoPath.resolve(patch.filePath.replace('\\', '/'))
        val current = if (Files.exists(target)) Files.readString(target) else ""
        val applied = diffs.apply(patch, current)
        if (applied is DiffApplyValidator.ApplyResult.Rejected) {
            return Outcome.Rejected(applied.rejection)
        }
        return Outcome.Accepted(
            ValidatedPatch.create(
                patch,
                checkNames = listOf("L1:path", "L2:diff-applies", "L3:patch-scope"),
            ),
        )
    }
}
