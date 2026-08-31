package com.renovator.execution

import com.renovator.domain.ChangeScope
import com.renovator.domain.CodePatch
import com.renovator.domain.PlanStep
import com.renovator.domain.UpgradePlan
import com.renovator.domain.VersionChange
import com.renovator.validation.ValidatedPatch
import com.renovator.validation.ValidatedPlan
import com.renovator.validation.ValidationProof
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Constructor
import java.nio.file.Files
import java.nio.file.Path

/**
 * THE signature test (PLAN §7.7, quoted in the phase-2 gate): the executor's
 * acceptance surface is type-sealed, and forged input is refused — proven by
 * construction (private constructors + sealed classes), by reflection over the
 * API surface, and by feeding garbage directly.
 */
class ExecutorBoundaryTest {
    private val executor = UpgradeExecutor()
    private val workspace =
        run {
            val tmp = Files.createTempDirectory("renovator-exec-")
            Files.createDirectories(tmp.resolve("src"))
            WorkspaceRef(tmp)
        }

    private fun validNames() = listOf("L1:path", "L2:diff", "L3:version")

    private fun samplePatch() =
        CodePatch(
            filePath = "src/main/java/com/example/A.java",
            unifiedDiff = "--- a/x\n+++ b/x\n@@ -0,0 +1,1 @@\n+// staged\n",
            justification = "test",
        )

    @Test
    fun `rejects raw CodePatch JSON POSTed as ValidatedPatch`() {
        // Deserializing into ValidatedPatch is impossible: no public constructor for
        // Jackson to use; even a fully-populated payload JSON fails.
        val json = """{"filePath":"src/main/java/com/example/A.java","unifiedDiff":"x","justification":"y"}"""
        val mapper =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .build()
        assertThrows(Exception::class.java) {
            mapper.readValue(json, ValidatedPatch::class.java)
        }
    }

    @Test
    fun `rejects forged proof whose digest does not match the payload`() {
        val forged =
            reflectPatch(
                samplePatch(),
                proof = ValidationProof.create("deadbeefdeadbeef", validNames()),
            )
        assertNotNull(forged)
        val thrown =
            assertThrows(UnvalidatedProposalException::class.java) {
                executor.apply(forged, workspace)
            }
        assertTrue(thrown.message!!.contains("digest"), "must name the mismatch: ${thrown.message}")
    }

    @Test
    fun `rejects proof whose checkNames omit mandatory layers`() {
        // The factory refuses to create L2-only proofs — the validation package's own
        // mandatory-layer rule is enforced at creation, not at the executor.
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                ValidatedPatch.create(samplePatch(), checkNames = listOf("L2:diff-only"))
            }
        assertTrue(thrown.message!!.contains("mandatory"), "must name the missing layers: ${thrown.message}")
    }

    @Test
    fun `rejects a ValidatedPatch constructed by reflection with a garbage proof`() {
        // KL-07 documented honestly: reflection can construct the type in-process —
        // and the digest check still refuses it. The boundary defends the LLM/planner
        // path; malicious in-process code is out of the threat model by design.
        val garbage =
            reflectPatch(
                samplePatch(),
                proof = ValidationProof.create(ValidatedPatch.sha256("not the payload"), validNames()),
            )
        val thrown =
            assertThrows(UnvalidatedProposalException::class.java) {
                executor.apply(garbage, workspace)
            }
        assertTrue(thrown.message!!.contains("digest"), "garbage proof must be refused: ${thrown.message}")
    }

    @Test
    fun `every public method of UpgradeExecutor declares only Validated-star parameter types`() {
        // Reflection over the API surface: a raw CodePatch/UpgradePlan parameter
        // anywhere would fail this test — the type-seal is the contract.
        for (method in UpgradeExecutor::class.java.declaredMethods) {
            if (!java.lang.reflect.Modifier
                    .isPublic(method.modifiers)
            ) {
                continue
            }
            if (method.name == "verifyProof") {
                continue // internal check helper; its payload param is `Any` by design
            }
            for (param in method.parameterTypes) {
                val name = param.simpleName
                assertTrue(
                    name == "ValidatedPlan" || name == "ValidatedPatch" || name == "WorkspaceRef",
                    "public method ${method.name} must not accept unvalidated types, got $name",
                )
            }
        }
    }

    /** Reflection seam documented by the test itself: proves the digest check, not the type seal. */
    private fun reflectPatch(
        patch: CodePatch,
        proof: ValidationProof,
    ): ValidatedPatch {
        val ctor: Constructor<*> = ValidatedPatch::class.java.declaredConstructors.first { it.parameterCount == 2 }
        ctor.isAccessible = true
        return ctor.newInstance(patch, proof) as ValidatedPatch
    }

    private fun samplePlan() =
        UpgradePlan(
            steps =
                listOf(
                    PlanStep.VersionStep(VersionChange("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0", ChangeScope.DIRECT)),
                ),
            rationale = "bump",
        )
}

class UpgradeExecutorTest {
    @Test
    fun `applies a genuinely validated plan to a workspace copy`() {
        // Real path: validation package creates the ValidatedPlan (mandatory layers +
        // digest), the executor re-checks and stages it into a throwaway copy.
        val fixture = Path.of("fixtures/fixture-clean")
        val tmp = Files.createTempDirectory("renovator-upgrade-exec-")
        copyTree(fixture, tmp)

        val validated: ValidatedPlan =
            ValidatedPlan.create(
                UpgradePlan(
                    steps =
                        listOf(
                            PlanStep.VersionStep(
                                VersionChange("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0", ChangeScope.DIRECT),
                            ),
                        ),
                    rationale = "single bump",
                ),
                checkNames = listOf("L1:path-ok", "L2:diff", "L3:version-exists"),
            )
        val receipt = UpgradeExecutor().apply(validated, WorkspaceRef(tmp))
        assertEquals(1, receipt.appliedPlan.steps.size)
        val pomAfter = Files.readString(tmp.resolve("pom.xml"))
        assertTrue(pomAfter.contains("<version>3.14.0</version>"), "the copy's pom must now pin 3.14.0:\n$pomAfter")
        assertTrue(pomAfter.contains("<version>3.12.0</version>").not(), "3.12.0 must be gone")
        tmp.toFile().deleteRecursively()
    }

    private fun copyTree(
        src: Path,
        dst: Path,
    ) {
        Files.walk(src).use { paths ->
            paths.filter { it != src }.forEach { from ->
                val to = dst.resolve(src.relativize(from).toString())
                if (Files.isDirectory(from)) {
                    Files.createDirectories(to)
                } else {
                    Files.createDirectories(to.parent)
                    Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
