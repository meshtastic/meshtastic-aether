package org.meshtastic.aether

import org.meshtastic.aether.ui.LinkChoice
import org.meshtastic.aether.ui.MemoryStore
import org.meshtastic.aether.ui.Settings
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppSessionTest {
    private fun app() = AppSession(Settings(MemoryStore()), session = null, failure = null, pending = null)

    // Regression: jSerialComm throws a plain RuntimeException for a port that is gone, which escaped the start and
    // crashed the desktop app when a saved radio was unplugged before its auto-connect.
    @Test
    fun `a serial port that is gone shows a start failure instead of throwing`() {
        val app = app()
        app.start(LinkChoice.Serial("/dev/aether-test-no-such-port", "Missing radio"))
        assertNull(app.session)
        assertNotNull(app.failure)
    }

    // Regression: the connect form accepts a WebSocket scheme in any case, but the endpoint check rejects it with
    // IllegalArgumentException, which escaped the Connect click.
    @Test
    fun `a WebSocket address the endpoint check rejects shows a start failure instead of throwing`() {
        val app = app()
        app.start(LinkChoice.WebSocket("WS://node.local"))
        assertNull(app.session)
        assertNotNull(app.failure)
    }
}
