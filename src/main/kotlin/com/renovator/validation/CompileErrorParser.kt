package com.renovator.validation

import com.renovator.domain.CompileError

/**
 * Parses javac diagnostics out of Maven output (PLAN §7 L4). Pattern from the real
 * captured sample (src/test/resources/buildlogs/compile-failure.log):
 *   [ERROR] /path/EscapeSqlFormatter.java:[17,16] cannot find symbol
 * Multi-line follow-ups (symbol:/location:) are dropped by design — the primary
 * diagnostic is the precise, nameable signal the planner needs.
 */
object CompileErrorParser {
    private val JAVAC_DIAGNOSTIC =
        Regex("""^\[ERROR\]\s+([^:\n]+\.java):\[(\d+),(\d+)\]\s+(.+)$""")

    fun parse(output: String): List<CompileError> =
        output
            .lines()
            .mapNotNull { line ->
                val m = JAVAC_DIAGNOSTIC.matchEntire(line.trimEnd())
                if (m == null) {
                    null
                } else {
                    CompileError(
                        filePath = m.groupValues[1],
                        line = m.groupValues[2].toInt(),
                        column = m.groupValues[3].toInt(),
                        message = m.groupValues[4],
                    )
                }
            }.distinct()
}
