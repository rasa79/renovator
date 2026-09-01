package com.renovator.agent

import com.renovator.agent.actions.AnalyzeRepositoryAction
import com.renovator.domain.DependencyTarget
import com.renovator.domain.RunRequest
import com.renovator.domain.UpgradeGoal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class AnalyzeRepositoryActionTest {
    private val action = AnalyzeRepositoryAction()
    private val goal =
        UpgradeGoal(
            targets = listOf(DependencyTarget("org.apache.commons", "commons-lang3", "3.12.0", "3.14.0")),
        )

    @Test
    fun `parses fixture-clean into RepoModel with commons-lang3 3_12_0`() {
        val model = action.analyze(RunRequest(Path.of("fixtures/fixture-clean"), goal))
        assertTrue(
            model.dependencies.any { it.groupId == "org.apache.commons" && it.artifactId == "commons-lang3" && it.version == "3.12.0" },
        )
        assertEquals("17", model.javaRelease)
    }

    @Test
    fun `detects enforcer convergence rule in fixture-transitive-conflict`() {
        val model = action.analyze(RunRequest(Path.of("fixtures/fixture-transitive-conflict"), goal))
        assertTrue(model.enforcerRules.contains("dependencyConvergence"), "rules: ${model.enforcerRules}")
    }
}
