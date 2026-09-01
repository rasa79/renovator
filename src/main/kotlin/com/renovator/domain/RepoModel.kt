package com.renovator.domain

/** Deterministic repository model produced by the analyzeRepository action (§5, §6). */
data class RepoModel(
    val dependencies: List<ResolvedDependency>,
    val enforcerRules: List<String>,
    val javaRelease: String,
)

data class ResolvedDependency(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val direct: Boolean,
)
