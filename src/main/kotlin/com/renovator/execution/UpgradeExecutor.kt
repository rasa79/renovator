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
                    stageVersionChange(step.change, workspace)
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

    /**
     * Applies a version change to the workspace pom, honoring its scope:
     *  - MANAGEMENT: upsert the artifact into `<dependencyManagement>` (create the
     *    section when absent, before `<dependencies>` per the XSD order) — the
     *    transitive-pin lane of the two-hop repair (Task 4.3);
     *  - DIRECT: plain bump of the declaration block, or a coordinate migration
     *    (groupId/artifactId change, e.g. the api-removal fixture) which rewrites
     *    the whole block for the FROM coordinates to the TO coordinates.
     * The management section and the dependency declarations are processed
     * separately so a DIRECT bump never touches the management pin and vice versa.
     */
    private fun stageVersionChange(
        change: com.renovator.domain.VersionChange,
        workspace: WorkspaceRef,
    ) {
        val pom = workspace.path.resolve("pom.xml")
        val text = Files.readString(pom)
        val mgmtRe = Regex("""<dependencyManagement>[\s\S]*?</dependencyManagement>""")
        val mgmt = mgmtRe.find(text)
        val body = if (mgmt == null) text else text.removeRange(mgmt.range)
        val newBody =
            when (change.scope) {
                com.renovator.domain.ChangeScope.MANAGEMENT -> {
                    stageManagementPin(change, body, mgmt?.value)
                }

                com.renovator.domain.ChangeScope.DIRECT -> {
                    stageDirectBumpOrMigration(change, body)
                }
            }
        val result =
            if (mgmt == null) {
                newBody
            } else {
                buildString {
                    append(newBody.substring(0, mgmt.range.first))
                    append(mgmt.value)
                    append(newBody.substring(mgmt.range.first))
                }
            }
        Files.writeString(pom, result)
    }

    /** MANAGEMENT lane: pin inside dependencyManagement. [body] is the pom without
     *  the management section; [existing] is the section itself (or null). */
    private fun stageManagementPin(
        change: com.renovator.domain.VersionChange,
        body: String,
        existing: String?,
    ): String {
        if (existing == null) {
            val section =
                "    <dependencyManagement>${System.lineSeparator()}        <dependencies>${System.lineSeparator()}            <dependency>${System.lineSeparator()}                <groupId>${change.groupId}</groupId>${System.lineSeparator()}                <artifactId>${change.artifactId}</artifactId>${System.lineSeparator()}                <version>${change.toVersion}</version>${System.lineSeparator()}            </dependency>${System.lineSeparator()}        </dependencies>${System.lineSeparator()}    </dependencyManagement>${System.lineSeparator()}${System.lineSeparator()}"
            val idx = body.indexOf("<dependencies>")
            require(idx >= 0) { "pom has no <dependencies> section to precede with a dependencyManagement pin" }
            return buildString {
                append(body.substring(0, idx))
                append(section)
                append(body.substring(idx))
            }
        }
        val entryRe =
            Regex(
                """<dependency>\s*<groupId>${change.groupId}</groupId>\s*<artifactId>${change.artifactId}</artifactId>\s*<version>([^<]+)</version>\s*</dependency>""",
            )
        val entry = entryRe.find(existing)
        if (entry == null) {
            // Append a new managed entry before the section's </dependencies>.
            val newEntry =
                "            <dependency>${System.lineSeparator()}                " +
                    "<groupId>${change.groupId}</groupId>${System.lineSeparator()}                " +
                    "<artifactId>${change.artifactId}</artifactId>${System.lineSeparator()}                " +
                    "<version>${change.toVersion}</version>${System.lineSeparator()}            </dependency>${System.lineSeparator()}"
            val close = existing.lastIndexOf("</dependencies>")
            return existing.substring(0, close) + newEntry + existing.substring(close)
        }
        val bumped =
            existing.replaceFirst(
                entry.value,
                entry.value.replaceFirst(
                    Regex("""<version>${entry.groupValues[1]}</version>"""),
                    "<version>${change.toVersion}</version>",
                ),
            )
        return bumped
    }

    /** DIRECT lane: bump the declaration, or migrate coordinates when the pom does
     *  not declare the target artifact yet (api-removal: commons-lang:commons-lang
     *  2.6 -> org.apache.commons:commons-lang3:3.14.0). [body] excludes management. */
    private fun stageDirectBumpOrMigration(
        change: com.renovator.domain.VersionChange,
        body: String,
    ): String {
        val marker =
            Regex(
                """(<artifactId>${change.artifactId}</artifactId>\s*<version>)[^<]+(</version>)""",
            )
        if (marker.containsMatchIn(body)) {
            return marker.replace(body) { m -> "${m.groupValues[1]}${change.toVersion}${m.groupValues[2]}" }
        }
        // Migration: rewrite the FROM-coordinate version block to the TO coordinates.
        val blockRe =
            Regex(
                """<dependency>\s*<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>\s*<version>([^<]+)</version>\s*</dependency>""",
            )
        val block =
            blockRe.findAll(body).firstOrNull { m ->
                m.groupValues[3] == change.fromVersion && m.groupValues[1] != change.groupId
            }
        require(block != null) {
            "pom does not declare from-version ${change.fromVersion} to migrate ${change.groupId}:${change.artifactId}:${change.toVersion}"
        }
        val replacedBlock =
            "<dependency>${System.lineSeparator()}            " +
                "<groupId>${change.groupId}</groupId>${System.lineSeparator()}            " +
                "<artifactId>${change.artifactId}</artifactId>${System.lineSeparator()}            " +
                "<version>${change.toVersion}</version>${System.lineSeparator()}        </dependency>"
        return buildString {
            append(body.substring(0, block.range.first))
            append(replacedBlock)
            append(body.substring(block.range.last + 1))
        }
    }
}
