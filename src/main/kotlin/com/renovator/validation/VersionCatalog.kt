package com.renovator.validation

/**
 * Layer-3 existence checks go through this seam: real runs use [HttpVersionCatalog],
 * hermetic tests a fake. Deliberately a one-method interface — the planner's replan
 * is the retry, so the catalog itself never retries (verified design, §7 L3).
 */
interface VersionCatalog {
    fun exists(
        groupId: String,
        artifactId: String,
        version: String,
    ): Boolean
}

/** Test double: a fixed set of known coordinates. */
class FakeVersionCatalog(
    private val known: Set<String> = emptySet(),
) : VersionCatalog {
    override fun exists(
        groupId: String,
        artifactId: String,
        version: String,
    ): Boolean = "$groupId:$artifactId:$version" in known
}
