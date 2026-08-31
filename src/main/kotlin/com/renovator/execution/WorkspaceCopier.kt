package com.renovator.execution

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * A pristine copy of a workspace that the sandbox may read. Never points at the
 * source tree: the executor's rule is "the source tree is never mounted" (D7).
 */
data class WorkspaceRef(
    val path: Path,
)

/**
 * Copies a source tree into a fresh temp directory, excluding build artifacts
 * (`target/`) and VCS metadata (`.git/`) at any depth. Timestamps are NOT
 * preserved (no COPY_ATTRIBUTES) so the copy is byte-stable, not mtime-stable —
 * a build in the sandbox cannot be invalidated by the host's clock.
 */
class WorkspaceCopier {
    /** Directories excluded at any depth. */
    private val excluded = setOf("target", ".git")

    fun copy(source: Path): WorkspaceRef {
        val destination = Files.createTempDirectory("renovator-workspace-")
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir != source && dir.fileName.toString() in excluded) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    Files.createDirectories(destination.resolve(source.relativize(dir)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    val to = destination.resolve(source.relativize(file))
                    Files.createDirectories(to.parent)
                    Files.copy(file, to, StandardCopyOption.REPLACE_EXISTING)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return WorkspaceRef(destination)
    }
}
