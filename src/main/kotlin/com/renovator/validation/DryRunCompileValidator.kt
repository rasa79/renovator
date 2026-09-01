package com.renovator.validation

import com.renovator.config.RenovatorProperties
import com.renovator.domain.CodePatch
import com.renovator.domain.CompileCheckResult
import com.renovator.domain.CompileError
import com.renovator.execution.DockerSandboxRunner
import com.renovator.execution.WorkspaceCopier
import com.renovator.execution.WorkspaceRef
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Layer 4 — dry-run compile (PLAN §7 L4, D9). All pending validated changes go to a
 * pristine [WorkspaceCopier] copy; `mvn -q compile` runs in the Docker sandbox; the
 * result is a typed [CompileCheckResult] with parsed javac diagnostics. The source
 * tree is never touched (copy + throwaway container, D7).
 *
 * Toggle (renovator.validation.dry-run-compile): ALWAYS | ON_COMMIT_CANDIDATE | OFF.
 * OFF short-circuits to a *skipped* result — explicitly distinguishable from
 * "compiled clean" via the [CompileCheckResult.skipped] flag (refinement noted in the
 * phase-2 report) — so a caller can never mistake "we didn't check" for "it passed".
 */
class DryRunCompileValidator(
    private val runner: DockerSandboxRunner =
        DockerSandboxRunner(RenovatorProperties().sandbox),
    private val validation: RenovatorProperties.Validation = RenovatorProperties().validation,
    private val diffValidator: DiffApplyValidator = DiffApplyValidator(),
    private val copier: WorkspaceCopier = WorkspaceCopier(),
) {
    /**
     * @param sourceTree the fixture/workspace root that holds the target file
     * @return skipped / passed with errors per the toggle and the compile outcome
     */
    fun check(
        patch: CodePatch,
        sourceTree: Path,
    ): CompileCheckResult {
        if (validation.dryRunCompile == RenovatorProperties.DryRunCompileMode.OFF) {
            return CompileCheckResult(success = true, errors = emptyList(), skipped = true)
        }
        val workspace = copier.copy(sourceTree)
        return try {
            compileWithPatch(patch, sourceTree, workspace)
        } finally {
            workspace.path.toFile().deleteRecursively()
        }
    }

    /** Plan variant: stage the WHOLE validated plan, then dry-run compile. */
    fun checkPlan(
        validatedPlan: com.renovator.validation.ValidatedPlan,
        sourceTree: Path,
    ): CompileCheckResult {
        if (validation.dryRunCompile == RenovatorProperties.DryRunCompileMode.OFF) {
            return CompileCheckResult(success = true, errors = emptyList(), skipped = true)
        }
        val workspace = copier.copy(sourceTree)
        return try {
            com.renovator.execution
                .UpgradeExecutor()
                .apply(validatedPlan, workspace)
            val build = runner.runBuild(workspace, listOf("compile"), Duration.ofMinutes(10))
            CompileCheckResult(success = build.success, errors = CompileErrorParser.parse(build.log.head + "\n" + build.log.tail))
        } finally {
            workspace.path.toFile().deleteRecursively()
        }
    }

    private fun compileWithPatch(
        patch: CodePatch,
        sourceTree: Path,
        workspace: WorkspaceRef,
    ): CompileCheckResult {
        // Stage the patch into the copy (L2 already attested it applies cleanly; this
        // is the re-application the executor will also do — see UpgradeExecutor).
        val target = workspace.path.resolve(patch.filePath.replace('\\', '/'))
        if (!Files.exists(target.parent)) {
            Files.createDirectories(target.parent)
        }
        val current = if (Files.exists(target)) Files.readString(target) else ""
        val applied = diffValidator.apply(patch, current)
        if (applied is DiffApplyValidator.ApplyResult.Rejected) {
            return CompileCheckResult(success = false, errors = emptyList())
        }
        Files.writeString(target, (applied as DiffApplyValidator.ApplyResult.Applied).content)

        val build = runner.runBuild(workspace, listOf("compile"), Duration.ofMinutes(10))
        val errors =
            if (build.success) {
                emptyList()
            } else {
                val parsed = CompileErrorParser.parse(build.log.head + "\n" + build.log.tail)
                if (parsed.isNotEmpty()) {
                    parsed
                } else {
                    listOf(
                        CompileError(
                            filePath = patch.filePath,
                            line = 0,
                            column = 0,
                            message = "compile failed without parseable javac diagnostics: ${build.failedGoals}",
                        ),
                    )
                }
            }
        return CompileCheckResult(success = build.success, errors = errors)
    }
}
