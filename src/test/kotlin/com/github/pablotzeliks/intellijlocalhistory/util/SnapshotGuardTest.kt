package com.github.pablotzeliks.intellijlocalhistory.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotGuardTest {
    @Test
    fun `guard starts inactive`() {
        assertFalse(SnapshotGuard.isActive())
    }

    @Test
    fun `guard is active inside withGuard block`() {
        SnapshotGuard.withGuard {
            assertTrue(SnapshotGuard.isActive())
        }
    }

    @Test
    fun `guard is inactive after withGuard block`() {
        SnapshotGuard.withGuard { }
        assertFalse(SnapshotGuard.isActive())
    }

    @Test
    fun `guard is inactive even if block throws exception`() {
        try {
            SnapshotGuard.withGuard { throw RuntimeException("test") }
        } catch (_: RuntimeException) { }
        assertFalse(SnapshotGuard.isActive())
    }

    @Test
    fun `withGuard returns block result`() {
        val result = SnapshotGuard.withGuard { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `guard stays active during nested withGuard blocks`() {
        SnapshotGuard.withGuard {
            assertTrue(SnapshotGuard.isActive())
            SnapshotGuard.withGuard {
                assertTrue(SnapshotGuard.isActive())
            }
            // Should still be active after the inner block finishes
            assertTrue(SnapshotGuard.isActive())
        }
        assertFalse(SnapshotGuard.isActive())
    }
}
