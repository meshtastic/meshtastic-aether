package org.meshtastic.aether

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNetworkGateTest {
    // Regression: a link that keeps retrying after a denial would put the system permission prompt up again on every
    // backoff, and a grant made later in system settings must still let the next retry through.
    @Test
    fun `after a denial retries only look and a later grant opens the link`() = runTest {
        val asks = mutableListOf<Boolean>()
        var granted = false
        val gate = LocalNetworkGate { ask ->
            asks += ask
            granted
        }

        assertFalse(gate.check())
        assertTrue(gate.denied)
        assertFalse(gate.check())
        granted = true
        assertTrue(gate.check())
        assertFalse(gate.denied)

        assertEquals(listOf(true, false, false), asks)
    }
}
