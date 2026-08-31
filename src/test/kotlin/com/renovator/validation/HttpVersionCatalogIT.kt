package com.renovator.validation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Exactly one network test proves the real catalog wiring (PLAN §7 L3:
 * "exactly one network test proves the real wiring"). Tagged network, runs in
 * the default verify per the plan.
 */
@Tag("network")
class HttpVersionCatalogIT {
    private val catalog = HttpVersionCatalog()

    @Test
    fun `commons-lang3 3_14_0 exists on central`() {
        assertTrue(catalog.exists("org.apache.commons", "commons-lang3", "3.14.0"))
    }

    @Test
    fun `version 99_99_99 does not exist on central`() {
        assertFalse(catalog.exists("org.apache.commons", "commons-lang3", "99.99.99"))
    }
}
