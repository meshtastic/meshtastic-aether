package org.meshtastic.aether.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UpdateTest {
    private val apkSha = "a".repeat(64)
    private val jarSha = "B".repeat(64)

    // The shape scripts/update-manifest.sh prints.
    private val manifest = """
        version=0.2.0-SNAPSHOT-abc1234
        code=57
        tag=snapshot
        android=aether-0.2.0-SNAPSHOT-abc1234-android.apk
        android.sha256=$apkSha
        desktop-linux-x64=aether-0.2.0-SNAPSHOT-abc1234-desktop-linux-x64.jar
        desktop-linux-x64.sha256=$jarSha
    """.trimIndent()

    // Regression: pairing a file with another key's digest, or not lowercasing it, fails every install on CHECKSUM.
    @Test
    fun `a manifest from the release script lists each platform file with its own digest`() {
        val build = PublishedBuild.parse(manifest)!!
        assertEquals("0.2.0-SNAPSHOT-abc1234", build.version)
        assertEquals(57L, build.code)
        assertEquals(Asset("aether-0.2.0-SNAPSHOT-abc1234-android.apk", apkSha), build.assets["android"])
        assertEquals(Asset("aether-0.2.0-SNAPSHOT-abc1234-desktop-linux-x64.jar", "b".repeat(64)), build.assets["desktop-linux-x64"])
        assertEquals(
            "https://github.com/meshtastic/meshtastic-aether/releases/download/snapshot/aether-0.2.0-SNAPSHOT-abc1234-android.apk",
            build.downloadUrl(build.assets.getValue("android")),
        )
    }

    // Regression: a file or tag with a slash would point the download anywhere on github.com.
    @Test
    fun `a file name or tag that is not a plain name is refused`() {
        val pathName = PublishedBuild.parse(manifest.replace("android=aether", "android=../../evil/aether"))!!
        assertNull(pathName.assets["android"])
        assertEquals(setOf("desktop-linux-x64"), pathName.assets.keys)
        assertNull(PublishedBuild.parse(manifest.replace("tag=snapshot", "tag=../x")))
    }

    // Regression: a truncated or empty manifest read as code 0 would never offer an update and never say it failed.
    @Test
    fun `a manifest without a positive code is no build at all`() {
        assertNull(PublishedBuild.parse(manifest.replace("code=57", "code=0")))
        assertNull(PublishedBuild.parse(manifest.replace("code=57\n", "")))
        assertNull(PublishedBuild.parse(""))
    }

    @Test
    fun `a digest that is not 64 hex digits drops only that platform`() {
        val build = PublishedBuild.parse(manifest.replace(apkSha, "a".repeat(63)))!!
        assertNull(build.assets["android"])
        assertEquals(setOf("desktop-linux-x64"), build.assets.keys)
    }

    // A release and the snapshot of its commit share a code; offering one over the other is a pointless reinstall.
    @Test
    fun `the same code is up to date and only a higher one is offered`() {
        val build = PublishedBuild.parse(manifest)!!
        assertIs<UpdateState.UpToDate>(compare(57, build))
        assertIs<UpdateState.UpToDate>(compare(58, build))
        assertIs<UpdateState.Available>(compare(56, build))
    }

    // Regression: checking the version before the key would send a newer package signed by someone else to the installer.
    @Test
    fun `a newer package signed with another key is refused for its key`() {
        val app = "org.meshtastic.aether"
        assertEquals(InstallFailure.DIFFERENT_KEY, packageVerdict(app, app, versionCode = 60, installedVersionCode = 57, sameKey = false))
        assertEquals(InstallFailure.NOT_THIS_APP, packageVerdict("com.example", app, 60, 57, sameKey = true))
        assertEquals(InstallFailure.NOT_NEWER, packageVerdict(app, app, 57, 57, sameKey = true))
        assertNull(packageVerdict(app, app, 58, 57, sameKey = true))
        assertNull(packageVerdict(app, app, 58, 57, sameKey = null))
    }
}
