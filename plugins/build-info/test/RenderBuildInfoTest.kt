package org.meshtastic.aether.buildinfo

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class RenderBuildInfoTest {
    @Test
    fun `a build with only some variables set fails instead of shipping as dev`() {
        assertFailsWith<IllegalStateException> {
            renderBuildInfo(mapOf("AETHER_BUILD_LABEL" to "0.2.0", "AETHER_BUILD_CODE" to "412"))
        }
    }

    @Test
    fun `the lowercase channel CI passes becomes the matching enum`() {
        val source = renderBuildInfo(
            mapOf(
                "AETHER_BUILD_LABEL" to "0.2.0-SNAPSHOT-abc1234",
                "AETHER_BUILD_CODE" to "412",
                "AETHER_BUILD_CHANNEL" to "snapshot",
            ),
        )
        assertContains(source, "val CHANNEL: Channel = Channel.SNAPSHOT")
        assertContains(source, "const val CODE: Long = 412")
        assertContains(source, "const val VERSION: String = \"0.2.0-SNAPSHOT-abc1234\"")
    }
}
