package org.meshtastic.aether.update

/** Which published builds a build follows for updates. */
enum class Channel {
    /** A local build: it follows nothing and never checks. */
    DEV,

    /** A tagged release: follows the latest release. */
    RELEASE,

    /** A build of `main`: follows the rolling `snapshot` prerelease. */
    SNAPSHOT,
}

/** What this build is. `scripts/stamp-build.sh` rewrites the values in CI; a local build keeps these defaults. */
object BuildInfo {
    const val VERSION: String = "dev"

    /** The commit count of the built commit, which is also the Android versionCode; 0 for a local build. */
    const val CODE: Long = 0

    val CHANNEL: Channel = Channel.DEV
}
