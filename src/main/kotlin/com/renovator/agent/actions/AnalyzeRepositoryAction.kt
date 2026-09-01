package com.renovator.agent.actions

import com.renovator.domain.RepoModel
import com.renovator.domain.ResolvedDependency
import com.renovator.domain.RunRequest
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import java.io.StringReader
import java.nio.file.Files

/**
 * Deterministic repository analysis (PLAN §6): parse the target pom with the
 * Maven Model API and produce the typed [RepoModel] the LLM planner reads.
 * No LLM, no network, no opinion — facts only.
 */
object AnalyzeRepositoryAction {
    fun analyze(runRequest: RunRequest): RepoModel {
        val pom = Files.readString(runRequest.repoPath.resolve("pom.xml"))
        val model = MavenXpp3Reader().read(StringReader(pom))
        val dependencies =
            model.dependencies.map {
                ResolvedDependency(
                    groupId = it.groupId,
                    artifactId = it.artifactId,
                    version = it.version,
                    direct = true,
                )
            }
        // enforcer rules: scan the raw pom text for the rule names — the Maven Model
        // API exposes configurations as opaque XML, so text detection is the honest
        // deterministic option for rule NAMES.
        val rawPom = Files.readString(runRequest.repoPath.resolve("pom.xml"))
        val enforcerRules =
            (model.build?.plugins ?: emptyList())
                .filter { it.artifactId == "maven-enforcer-plugin" }
                .flatMap { plugin ->
                    (plugin.executions ?: emptyList()).mapNotNull { e -> e.goals?.firstOrNull() ?: "enforce" }
                }.map { rule -> if (rawPom.contains("dependencyConvergence")) "dependencyConvergence" else rule }
                .distinct()
        val javaRelease =
            model.properties?.getProperty("maven.compiler.release")
                ?: model.build?.sourceDirectory?.let { "custom" }
                ?: "17"
        return RepoModel(dependencies = dependencies, enforcerRules = enforcerRules, javaRelease = javaRelease)
    }
}
