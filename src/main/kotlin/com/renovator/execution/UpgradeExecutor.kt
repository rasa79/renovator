package com.renovator.execution

import com.renovator.domain.CodePatch
import com.renovator.domain.UpgradePlan
import com.renovator.validation.ProposalJson
import com.renovator.validation.ValidatedPatch
import com.renovator.validation.ValidatedPlan
import java.nio.file.Files
import java.nio.file.Path

/** Thrown when the executor receives something it can prove was never validated. */
class UnvalidatedProposalException(
    message: String,
) : IllegalArgumentException(message)

// LEARN[006] The enforcement boundary: validation is code, not prompts
// Why this way: once proposals cross into execution, nothing may be able to argue
//   about them. A prompt can say "always validate first" — a type can't be argued
//   with. So the executor's public methods accept ONLY ValidatedPlan/ValidatedPatch
//   (types with private constructors, sealed per §7.6), and even a Validated* that
//   somehow exists must still be refused unless its proof's digest recomputes from
//   the payload and the proof names the mandatory layers. Validation is a computed
//   fact, not an annotation someone read.
// Good sides: the enforcement point is locatable (one class); misuse fails loudly
//   (UnvalidatedProposalException, not a silent skip); the ExecutorBoundaryTest
//   proves the API surface by reflection so it can't drift; the blackboard never
//   holds an unvalidated action input.
// Drawbacks: the API is clunkier (wrapping types, factories); JVM reflection can
//   still build the types in-process (KL-07 — documented, accepted: the boundary
//   defends the LLM/planner path, not malicious in-process code); and proof fields
//   must be serialized deterministically — hence ProposalJson, one shared mapper.
// Concept: think "sealed envelope with a wax seal": the seal (digest + mandatory
//   layers) is checked twice — once when the envelope is closed by the validation
//   package, once when the executor opens it. The type system prevents CARELESS
//   code from making its own envelope; the digest prevents clever code from forging
//   one. Enforcement lives at the boundary because prompts are suggestions and
//   types are facts.
// See also: PLAN §4.2, §7.6, §7.7; validation/Validated.kt; LEARN[007]-like layer reasoning
open class UpgradeExecutor {
    /**
     * Applies a validated plan's steps to a workspace copy. Every step's payload is
     * re-hashed against the proof; steps are staged (patch steps write files,
     * version steps rewrite the pom) on a WorkspaceCopier copy — the source tree
     * is never touched (D7).
     *
     * // TODO(review) KL-07: JVM reflection can construct Validated* in-process; the
     * // boundary here defends the LLM/planner path, not malicious in-process code.
     */
    fun apply(
        plan: ValidatedPlan,
        workspace: WorkspaceRef,
    ): ExecutionReceipt {
        verifyProof(plan.plan, plan.proof.checkNames, plan.proof.contentDigestSha256)
        // Stage the plan: patch steps write files via the staged diff; version steps
        // rewrite the pom's dependencyManagement/dependencies blocks (phased in Task 3.1).
        for (step in plan.plan.steps) {
            when (step) {
                is com.renovator.domain.PlanStep.PatchStep -> {
                    stagePatchFile(step.patch, workspace)
                }

                is com.renovator.domain.PlanStep.VersionStep -> {
                    stageVersionChange(
                        step.change.groupId,
                        step.change.artifactId,
                        step.change.toVersion,
                        workspace,
                    )
                }
            }
        }
        return ExecutionReceipt(appliedPlan = plan.plan, workspace = workspace)
    }

    /** Boundary check for a single validated patch (used by validatePatch->apply path). */
    fun apply(
        patch: ValidatedPatch,
        workspace: WorkspaceRef,
    ): ExecutionReceipt {
        verifyProof(patch.patch, patch.proof.checkNames, patch.proof.contentDigestSha256)
        stagePatchFile(patch.patch, workspace)
        return ExecutionReceipt(
            appliedPlan =
                UpgradePlan(
                    listOf(
                        com.renovator.domain.PlanStep
                            .PatchStep(patch.patch),
                    ),
                    "single patch",
                ),
            workspace = workspace,
        )
    }

    /** THE check: digest must recompute from the payload; mandatory layers must be named. */
    protected fun verifyProof(
        payload: Any,
        checkNames: List<String>,
        digest: String,
    ) {
        val recomputed = ValidatedPatch.sha256(ProposalJson.canonical(payload))
        if (recomputed != digest) {
            throw UnvalidatedProposalException(
                "proof digest does not match the payload (recomputed $recomputed, proof has $digest)",
            )
        }
        val layers = checkNames.mapNotNull { it.substringBefore(":").takeIf { c -> c in setOf("L1", "L2", "L3") } }.toSet()
        if (layers != setOf("L1", "L2", "L3")) {
            throw UnvalidatedProposalException(
                "proof must name mandatory layers L1, L2, L3 — was: $checkNames",
            )
        }
    }

    private fun stagePatchFile(
        patch: CodePatch,
        workspace: WorkspaceRef,
    ) {
        val target = workspace.path.resolve(patch.filePath.replace('\\', '/'))
        if (!Files.exists(target.parent)) {
            Files.createDirectories(target.parent)
        }
        val current = if (Files.exists(target)) Files.readString(target) else ""
        val applied =
            com.renovator.validation
                .DiffApplyValidator()
                .apply(patch, current)
        require(applied is com.renovator.validation.DiffApplyValidator.ApplyResult.Applied) {
            "staged patch does not apply (should never happen: L2 attested it): $applied"
        }
        Files.writeString(target, (applied as com.renovator.validation.DiffApplyValidator.ApplyResult.Applied).content)
    }

    /** Minimal dependency-block version bump on the workspace pom (Task 3.1 matures it). */
    private fun stageVersionChange(
        groupId: String,
        artifactId: String,
        toVersion: String,
        workspace: WorkspaceRef,
    ) {
        val pom = workspace.path.resolve("pom.xml")
        val text = Files.readString(pom)
        val marker =
            Regex(
                """(<artifactId>$artifactId</artifactId>\s*<version>)[^<]+(</version>)""",
            )
        require(marker.containsMatchIn(text)) { "pom does not declare dependency $groupId:$artifactId" }
        Files.writeString(pom, marker.replace(text) { m -> "${m.groupValues[1]}$toVersion${m.groupValues[2]}" })
    }
}
