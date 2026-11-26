package org.meshtastic.ambient.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelayResolverTest {
    // Regression: guessing between two nodes that share a low byte draws a path through the wrong node.
    @Test
    fun `a byte matching exactly one other node names it`() {
        assertEquals(0x1234AB01L, resolveRelay(listOf(0x1234AB01L, 0x99000042L), 0x01, source = 0x99000042L))
    }

    @Test
    fun `two nodes with the same low byte give no answer`() {
        assertNull(resolveRelay(listOf(0xAAAA0007L, 0xBBBB0007L), 0x07, source = 1L))
    }

    @Test
    fun `the source and our own node are never the relay`() {
        assertNull(resolveRelay(listOf(0x00000007L), 0x07, source = 0x00000007L))
        assertNull(resolveRelay(listOf(0x00000007L), 0x07, source = 1L, self = 0x00000007L))
    }

    @Test
    fun `no byte or a zero byte means direct`() {
        assertNull(resolveRelay(listOf(0x00000000L, 0x00000001L), null, source = 5L))
        assertNull(resolveRelay(listOf(0x00000000L), 0, source = 5L))
    }
}
