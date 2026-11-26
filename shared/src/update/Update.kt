package org.meshtastic.aether.update

import kotlinx.coroutines.flow.StateFlow

const val REPOSITORY_URL = "https://github.com/meshtastic/meshtastic-aether"

/** The manifest each published build carries, at a URL that always answers with the channel's newest. */
fun manifestUrl(channel: Channel): String? = when (channel) {
    Channel.DEV -> null
    Channel.RELEASE -> "$REPOSITORY_URL/releases/latest/download/$MANIFEST_NAME"
    Channel.SNAPSHOT -> "$REPOSITORY_URL/releases/download/snapshot/$MANIFEST_NAME"
}

const val MANIFEST_NAME = "update.properties"

/** One downloadable file of a published build. */
data class Asset(val name: String, val sha256: String)

/** A published build as its `update.properties` describes it. */
data class PublishedBuild(
    val version: String,
    /** The build's commit count; a higher one is newer. */
    val code: Long,
    /** The release's tag, `snapshot` or `vX.Y.Z`. */
    val tag: String,
    /** Files by platform key: `android`, `desktop-linux-x64`, `desktop-macos-arm64`. */
    val assets: Map<String, Asset>,
    /** Where the files are; the release's download folder unless a test manifest elsewhere says they sit beside it. */
    val downloadBase: String = "$REPOSITORY_URL/releases/download/$tag",
) {
    val pageUrl: String get() = "$REPOSITORY_URL/releases/tag/$tag"

    fun downloadUrl(asset: Asset): String = "$downloadBase/${asset.name}"

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val SAFE_NAME = Regex("[A-Za-z0-9._+-]+")
        private val SAFE_TAG = Regex("snapshot|v\\d+\\.\\d+\\.\\d+")

        /**
         * Reads an `update.properties`: `key=value` lines, `#` comments, and per platform `<key>=<file>` with
         * `<key>.sha256=<hex>`. Null when `version`, a positive `code` or a valid `tag` is missing. A platform whose
         * file name or digest is malformed is left out rather than failing the whole build.
         */
        fun parse(text: String): PublishedBuild? {
            val values = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() } }
                .toMap()
            val version = values["version"]?.takeIf { it.isNotEmpty() } ?: return null
            val code = values["code"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val tag = values["tag"]?.takeIf { SAFE_TAG.matches(it) } ?: return null
            val assets = values.keys
                .filter { !it.endsWith(".sha256") && it !in HEADER_KEYS }
                .mapNotNull { key ->
                    val name = values.getValue(key).takeIf { SAFE_NAME.matches(it) } ?: return@mapNotNull null
                    val sha = values["$key.sha256"]?.lowercase()?.takeIf { SHA256.matches(it) } ?: return@mapNotNull null
                    key to Asset(name, sha)
                }
                .toMap()
            return PublishedBuild(version, code, tag, assets)
        }

        private val HEADER_KEYS = setOf("version", "code", "tag")
    }
}

/** How this build stands against its channel's newest. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data class UpToDate(val latest: PublishedBuild) : UpdateState

    data class Available(val latest: PublishedBuild) : UpdateState

    /** The channel has nothing published yet (GitHub answers 404 before the first release or mid-replace). */
    data object NothingPublished : UpdateState

    /** The manifest could not be fetched or read. */
    data object Failed : UpdateState
}

/** Where [runningCode] stands against [latest]; a release and the snapshot of one commit share a code, so only a higher one is newer. */
fun compare(runningCode: Long, latest: PublishedBuild): UpdateState =
    if (latest.code > runningCode) UpdateState.Available(latest) else UpdateState.UpToDate(latest)

/**
 * Why a downloaded Android package must not go to the installer, or null when it may. [sameKey] is null when Android
 * could not read either side's signers, and the installer's own check then decides.
 */
fun packageVerdict(packageName: String?, appPackage: String, versionCode: Long, installedVersionCode: Long, sameKey: Boolean?): InstallFailure? =
    when {
        packageName != appPackage -> InstallFailure.NOT_THIS_APP
        sameKey == false -> InstallFailure.DIFFERENT_KEY
        versionCode <= installedVersionCode -> InstallFailure.NOT_NEWER
        else -> null
    }

/** How long to wait before the next check: a day after an answer, an hour after a failure or an empty channel. */
fun nextCheckDelayMillis(state: UpdateState): Long = when (state) {
    is UpdateState.UpToDate, is UpdateState.Available -> DAY_MILLIS
    else -> HOUR_MILLIS
}

private const val HOUR_MILLIS = 60 * 60 * 1000L
private const val DAY_MILLIS = 24 * HOUR_MILLIS

/** Where installing an update stands. */
sealed interface InstallState {
    data object Idle : InstallState

    /** [percent] of the file is in, or null while the server gives no size. */
    data class Downloading(val percent: Int?) : InstallState

    /** With the system installer (Android) or being swapped in before a restart (desktop). */
    data object Installing : InstallState

    data class Failed(val failure: InstallFailure, val detail: String? = null) : InstallState
}

enum class InstallFailure {
    /** The download did not finish. */
    DOWNLOAD,

    /** The file's SHA-256 is not the one the manifest gives. */
    CHECKSUM,

    /** The file is not an Android package for this app. */
    NOT_THIS_APP,

    /** The package is signed with another key, so Android would refuse it over this install. */
    DIFFERENT_KEY,

    /** The package's version code is not above the running one, so Android would refuse it as a downgrade. */
    NOT_NEWER,

    /** The system installer, or the file swap on desktop, refused or failed. */
    INSTALLER,
}

/** How the platform gets an update in: installs it itself, or only points at the download page. */
enum class InstallMode { INSTALL, INSTALL_AND_RESTART, OPEN_PAGE }

/** Checks this build's channel for a newer build and installs it when asked. */
interface Updater {
    val update: StateFlow<UpdateState>
    val install: StateFlow<InstallState>

    /** What [install] does for [latest] on this platform. */
    fun modeFor(latest: PublishedBuild): InstallMode

    /** Fetches the manifest and returns the answer, which also lands in [update]; checks never overlap. */
    suspend fun check(): UpdateState

    /** Installs [latest], or opens its page where this platform cannot install it. */
    fun install(latest: PublishedBuild)

    /** Opens [latest]'s release page in the browser. */
    fun openPage(latest: PublishedBuild)
}

/** This process's updater, or null where updates come another way (an app store, the web) or on a local build. */
internal expect fun createUpdater(): Updater?
