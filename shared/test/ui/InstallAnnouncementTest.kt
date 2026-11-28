package org.meshtastic.aether.ui

import org.meshtastic.aether.update.InstallMode
import org.meshtastic.aether.update.InstallState
import kotlin.test.Test
import kotlin.test.assertEquals

class InstallAnnouncementTest {
    // Regression: the install line is a live region and its text changed on every percent, so a screen reader read
    // "Downloading 1%", "Downloading 2%" and on through the whole download.
    @Test
    fun `a download announces the same words at every percent`() {
        val spoken = listOf(1, 2, 57, 100, null).map { installAnnouncement(InstallState.Downloading(it), InstallMode.INSTALL) }
        assertEquals(listOf("Downloading"), spoken.distinct())
    }
}
