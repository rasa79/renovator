package com.renovator.domain

import java.nio.file.Path

/**
 * The run's submission: what to upgrade and where. Bound to the agent blackboard
 * as the process input (the action palette's `UpgradeGoal`-first signature is the
 * plan's abstraction; the repo path must ride something — this is that thing).
 */
data class RunRequest(
    val repoPath: Path,
    val goal: UpgradeGoal,
)
