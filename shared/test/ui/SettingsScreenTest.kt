package org.meshtastic.aether.ui

import org.meshtastic.aether.update.PublishedBuild
import org.meshtastic.aether.update.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsScreenTest {
    private fun latest(code: Long) = PublishedBuild("1.2.0", code, "v1.2.0", emptyMap())

    // Regression: a snapshot ahead of the newest release read "Up to date with 1.2.0", an older version than the one running.
    @Test
    fun `a build ahead of the track says nothing is newer and not up to date`() {
        assertEquals("Nothing newer than this build; the latest is 1.2.0", checkStatus(UpdateState.UpToDate(latest(50)), runningCode = 70))
        assertEquals("Up to date with 1.2.0", checkStatus(UpdateState.UpToDate(latest(70)), runningCode = 70))
    }
}
