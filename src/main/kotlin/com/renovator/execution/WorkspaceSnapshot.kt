package com.renovator.execution

import java.nio.file.Files
import java.nio.file.Path

/**
 * A sandboxed copy of the repo after validated changes were applied (PLAN §6:
 * `applyValidatedChanges` → `WorkspaceSnapshot`). Immutable pointer: the tree it
 * points at is created by WorkspaceCopier and owned by the run.
 */
data class WorkspaceSnapshot(
    val ref: WorkspaceRef,
    val sourceHash: String,
) {
    fun pom(): String = Files.readString(ref.path.resolve("pom.xml"))

    fun bytes(): ByteArray = sourceHash.toByteArray()
}

/** Computes a stable hash over a tree (sorted relative paths + content). */
object TreeHasher {
    fun of(root: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .sorted()
                .forEach { file ->
                    digest.update(root.relativize(file).toString().toByteArray())
                    digest.update(Files.readAllBytes(file))
                }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
