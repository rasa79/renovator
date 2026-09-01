package com.renovator.validation

import com.renovator.config.RenovatorProperties
import com.renovator.domain.Constraint
import com.renovator.domain.ValidationRejection
import com.renovator.domain.VersionChange
import org.apache.maven.artifact.versioning.ComparableVersion
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import java.io.StringReader

/**
 * Layer 3 — domain invariants (PLAN §7 L3). Pure function over a candidate
 * [VersionChange] plus the post-edit pom XML:
 *  1. existence            — the target version is known to the [catalog]
 *  2. monotonic increase   — ComparableVersion(to) > ComparableVersion(from)
 *  3. snapshot policy      — no -SNAPSHOT unless config + constraints allow
 *  4. pom re-parse         — valid Maven model, <modelVersion> intact,
 *                            <repositories> inside the configured allowlist
 * Every failure is a typed [ValidationRejection] so the planner can replan.
 */
class DomainInvariantValidator(
    private val catalog: VersionCatalog,
    private val validation: RenovatorProperties.Validation = RenovatorProperties().validation,
) {
    fun check(
        change: VersionChange,
        constraints: List<Constraint> = emptyList(),
        pomXmlAfterEdit: String,
    ): ValidationRejection? {
        // 1. existence
        if (!catalog.exists(change.groupId, change.artifactId, change.toVersion)) {
            return rejection(
                "L3:version-exists",
                "version ${change.artifactId}:${change.toVersion} does not exist in the version catalog",
                "${change.groupId}:${change.artifactId}:${change.toVersion}",
            )
        }
        // 2. monotonic increase (comparable even across a coordinate migration)
        val from = ComparableVersion(change.fromVersion)
        val to = ComparableVersion(change.toVersion)
        if (to <= from) {
            return rejection(
                "L3:monotonic",
                "version change is not an increase: ${change.fromVersion} -> ${change.toVersion}",
                "${change.fromVersion}->${change.toVersion}",
            )
        }
        // 3. snapshots
        if (change.toVersion.contains("-SNAPSHOT")) {
            val allowed = validation.allowSnapshots && constraints.none { it is Constraint.NoSnapshots }
            if (!allowed) {
                return rejection(
                    "L3:snapshots",
                    "snapshot version ${change.toVersion} is not allowed",
                    change.toVersion,
                )
            }
        }
        // 4. post-edit pom re-parse (supply-chain guard)
        return checkPom(pomXmlAfterEdit)
    }

    /**
     * L3 domain invariant for a CODE patch: code patches touch code. The build file
     * (pom.xml) is owned by PLAN steps — VersionStep coordinate edits run the whole
     * L3 pipeline (existence, monotonic, snapshots, repository allowlist); a patch
     * that edits the pom would bypass exactly the checks that decide whether a
     * version change is admissible. The invariant keeps the two lanes apart. VCS
     * metadata and build output are banned too (belt-and-braces alongside the L1
     * forbidden patterns, which must not be the only line of defense).
     */
    fun check(patch: com.renovator.domain.CodePatch): ValidationRejection? {
        val path = patch.filePath.replace('\\', '/')
        if (path == "pom.xml" || path.endsWith("/pom.xml")) {
            return rejection(
                "L3:patch-build-file",
                "code patches must not modify the build file (pom.xml) — version and dependency edits are plan steps",
                path,
            )
        }
        if (path.startsWith(".git/") || path.contains("/target/")) {
            return rejection(
                "L3:patch-forbidden-area",
                "patch targets a forbidden area (VCS metadata or build output)",
                path,
            )
        }
        return null
    }

    private fun checkPom(pomXml: String): ValidationRejection? {
        val model =
            try {
                MavenXpp3Reader().read(StringReader(pomXml))
            } catch (e: Exception) {
                return rejection("L3:pom-parse", "post-edit pom is not a valid Maven model: ${e.message}", pomXml.take(400))
            }
        if (model.modelVersion != "4.0.0") {
            return rejection("L3:model-version", "modelVersion must be 4.0.0, was '${model.modelVersion}'", model.modelVersion.orEmpty())
        }
        for (repo in model.repositories) {
            val url = repo.url.orEmpty()
            if (validation.allowedRepositories.none { url.startsWith(it) }) {
                return rejection(
                    "L3:repository-allowlist",
                    "repository '${repo.id}' ($url) is outside the allowlist",
                    url,
                )
            }
        }
        return null
    }

    private fun rejection(
        check: String,
        reason: String,
        offending: String,
    ) = ValidationRejection(checkName = check, reason = reason, offendingContent = offending)
}
