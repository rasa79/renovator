package com.renovator.agent.actions

import com.renovator.domain.CodePatch
import com.renovator.domain.RunRequest
import com.renovator.domain.ValidationRejection
import com.renovator.validation.DiffApplyValidator
import com.renovator.validation.PathWhitelistValidator
import com.renovator.validation.ValidatedPatch
import org.springframework.stereotype.Component
import java.nio.file.Files

/**
 * Deterministic patch validation (PLAN §6: `validatePatch` runs L1–L2): the path
 * must be whitelisted AND the unified diff must apply cleanly to the current file
 * content. The resulting [ValidatedPatch] is the only patch type the executor
 * accepts (Task 2.7 border).
 */
@Component
class ValidatePatchAction(
    private val whitelist: PathWhitelistValidator = PathWhitelistValidator(),
    private val diffs: DiffApplyValidator = DiffApplyValidator(),
) {
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
        val target = runRequest.repoPath.resolve(patch.filePath.replace('\\', '/'))
        val current = if (Files.exists(target)) Files.readString(target) else ""
        val applied = diffs.apply(patch, current)
        if (applied is DiffApplyValidator.ApplyResult.Rejected) {
            return Outcome.Rejected(applied.rejection)
        }
        return Outcome.Accepted(
            ValidatedPatch.create(
                patch,
                checkNames = listOf("L1:path", "L2:diff-applies"),
            ),
        )
    }
}
