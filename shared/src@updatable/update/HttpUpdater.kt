package org.meshtastic.aether.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.meshtastic.aether.log.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

private const val TAG = "update"

/** A failed check keeps an update already offered; otherwise it reports the failure. */
private fun checkFailed(previous: UpdateState, e: Exception): UpdateState {
    Log.w(TAG, "check failed: ${e.message}")
    return if (previous is UpdateState.Available) previous else UpdateState.Failed
}
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000
private const val MAX_MANIFEST_BYTES = 64 * 1024
private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
private const val READ_BUFFER_BYTES = 8 * 1024
private const val FULL_PERCENT = 100

/** `AETHER_UPDATE_MANIFEST` points the check at another manifest, for testing an install against a local server. */
internal fun manifestOverride(): String? = System.getenv("AETHER_UPDATE_MANIFEST")?.takeIf { it.isNotBlank() }

/**
 * The manifest check and verified download that Android and desktop share; each platform says how a downloaded file
 * goes in. One per process, so a download outlives the dialog that started it.
 */
internal abstract class HttpUpdater(
    initialManifest: String,
    private val runningCode: Long,
    /** A test manifest names files that sit beside it, not on GitHub. */
    private val assetsBesideManifest: Boolean = false,
    /** The manifest a track's builds publish; tests point it at a local server. */
    private val manifestFor: (UpdateTrack) -> String? = { manifestUrl(it.channel) },
) : Updater {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val update: StateFlow<UpdateState> = _update.asStateFlow()
    protected val installState = MutableStateFlow<InstallState>(InstallState.Idle)
    override val install: StateFlow<InstallState> = installState.asStateFlow()
    @Volatile private var currentManifest = initialManifest
    private val checkLock = Mutex()

    /** Makes a track change and a check's write atomic: a check never lands an old track's answer after [follow]. */
    private val stateLock = Any()
    private var installing: Job? = null

    override fun follow(track: UpdateTrack) {
        // A test manifest is the one being tested; a track has no say over it.
        if (assetsBesideManifest) return
        val next = manifestFor(track) ?: return
        synchronized(stateLock) {
            currentManifest = next
            _update.value = UpdateState.Idle
        }
    }

    override suspend fun check(): UpdateState = checkLock.withLock {
        val manifest = currentManifest
        val previous = _update.value
        // An offered update stays offered while the next check runs, so the Update button does not blink.
        if (previous !is UpdateState.Available) _update.value = UpdateState.Checking
        val answer = try {
            fetchLatest(manifest, assetsBesideManifest)?.let { latest ->
                Log.i(TAG, "running $runningCode, published ${latest.version} (${latest.code})")
                compare(runningCode, latest)
            } ?: UpdateState.Failed
        } catch (e: CancellationException) {
            synchronized(stateLock) { if (manifest == currentManifest) _update.value = previous }
            throw e
        } catch (_: NotPublished) {
            // The snapshot's manifest is briefly missing while it is replaced; an offer already made stands.
            if (previous is UpdateState.Available) previous else UpdateState.NothingPublished
        } catch (e: IOException) {
            checkFailed(previous, e)
        } catch (e: SecurityException) {
            checkFailed(previous, e)
        }
        // The track changed while this ran: the answer belongs to the old one, and follow() already reset the state.
        synchronized(stateLock) {
            if (manifest != currentManifest) return@withLock _update.value
            _update.value = answer
        }
        answer
    }

    override fun install(latest: PublishedBuild) {
        if (installing?.isActive == true || installState.value == InstallState.Installing) return
        if (assetFor(latest) == null || modeFor(latest) == InstallMode.OPEN_PAGE) {
            browse(latest.pageUrl)
            return
        }
        installing = scope.launch {
            installState.value = InstallState.Downloading(null)
            // Nothing here may crash the app: a bad URL arrives as an IOException, like file errors.
            try {
                downloadAndInstall(latest)
            } catch (e: IOException) {
                downloadFailed(e)
            } catch (e: SecurityException) {
                downloadFailed(e)
            }
        }
    }

    private suspend fun downloadAndInstall(latest: PublishedBuild) {
        // An offer can be a day old and a snapshot's files are pruned on every push, so install what is published now.
        val current = when (val fresh = check()) {
            is UpdateState.Available -> fresh.latest
            is UpdateState.UpToDate -> {
                installState.value = InstallState.Idle
                return
            }
            else -> latest
        }
        val asset = assetFor(current)
        if (asset == null) {
            installState.value = InstallState.Idle
            browse(current.pageUrl)
            return
        }
        val file = downloadTarget(asset)
        val sha = download(current.downloadUrl(asset), file) { installState.value = InstallState.Downloading(it) }
        if (sha != asset.sha256) {
            file.delete()
            Log.w(TAG, "checksum mismatch: got $sha, want ${asset.sha256}")
            installState.value = InstallState.Failed(InstallFailure.CHECKSUM)
        } else {
            installFile(file, current)?.let { installState.value = it }
        }
    }

    private fun downloadFailed(e: Exception) {
        Log.w(TAG, "download failed: ${e.message}")
        installState.value = InstallState.Failed(InstallFailure.DOWNLOAD, e.message)
    }

    /** The asset this platform installs, or null when the build carries none for it. */
    protected abstract fun assetFor(latest: PublishedBuild): Asset?

    /** Where the download goes; replaced if present. */
    protected abstract fun downloadTarget(asset: Asset): File

    /**
     * Puts the verified [file] in; returns the outcome, or null when the platform reports it later through
     * [installState].
     */
    protected abstract suspend fun installFile(file: File, latest: PublishedBuild): InstallState?

    override fun openPage(latest: PublishedBuild) = browse(latest.pageUrl)

    protected abstract fun browse(url: String)

    companion object {
        /** The build [manifest] publishes; with [assetsBesideManifest] its files are fetched from beside it. */
        private suspend fun fetchLatest(manifest: String, assetsBesideManifest: Boolean): PublishedBuild? =
            PublishedBuild.parse(fetchText(manifest))
                ?.let { if (assetsBesideManifest) it.copy(downloadBase = manifest.substringBeforeLast('/')) else it }

        private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
            open(url).useResponse { connection ->
                val bytes = connection.inputStream.use { it.readNBytesCompat(MAX_MANIFEST_BYTES + 1) }
                if (bytes.size > MAX_MANIFEST_BYTES) throw IOException("manifest over $MAX_MANIFEST_BYTES bytes")
                bytes.decodeToString()
            }
        }

        /**
         * Downloads [url] into [file], replacing it, and returns the SHA-256 of what arrived in lowercase hex.
         * [onProgress] gets each new whole percent, or null when the response gives no length. A failed download
         * throws [IOException] and leaves no file behind.
         */
        suspend fun download(url: String, file: File, onProgress: (Int?) -> Unit): String =
            withContext(Dispatchers.IO) {
                var complete = false
                try {
                    open(url).useResponse { connection -> copyAndDigest(connection, file, onProgress) }
                        .also { complete = true }
                } finally {
                    if (!complete) file.delete()
                }
            }

        private fun copyAndDigest(connection: HttpURLConnection, file: File, onProgress: (Int?) -> Unit): String {
            val total = connection.contentLengthLong.takeIf { it > 0 }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            val progress = DownloadProgress(total, onProgress)
            connection.inputStream.use { input ->
                file.outputStream().use { out ->
                    var read = input.read(buffer)
                    while (read != -1) {
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        progress.add(read)
                        read = input.read(buffer)
                    }
                }
            }
            progress.checkComplete()
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** A GET that follows GitHub's redirect to its download host, which is a different origin. */
        private fun open(url: String): HttpURLConnection {
            var target = url
            repeat(MAX_REDIRECTS) {
                val connection = connect(target)
                val status = connection.responseCode
                if (status in REDIRECT_STATUS) {
                    target = redirectTarget(connection, target, status)
                    return@repeat
                }
                if (status !in SUCCESS_STATUS) {
                    connection.disconnect()
                    val notFound = status == HttpURLConnection.HTTP_NOT_FOUND
                    throw if (notFound) NotPublished() else IOException("HTTP $status")
                }
                return connection
            }
            throw IOException("too many redirects")
        }

        /** An unconnected GET of [target]; a malformed, relative or non-HTTP URL is an [IOException] like any fetch. */
        private fun connect(target: String): HttpURLConnection {
            val uri = parseUri(target)
            if (!uri.isAbsolute) throw IOException("not an absolute URL: $target")
            val connection = uri.toURL().openConnection() as? HttpURLConnection
                ?: throw IOException("not an HTTP URL: $target")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Aether")
            return connection
        }

        /** Where the redirect answering [target] points: never off HTTPS, though a local test server may use HTTP. */
        private fun redirectTarget(connection: HttpURLConnection, target: String, status: Int): String {
            val location = connection.getHeaderField("Location") ?: throw IOException("HTTP $status without Location")
            connection.disconnect()
            val from = parseUri(target)
            val next = from.resolve(parseUri(location))
            if (next.scheme != "https" && from.scheme == "https") throw IOException("redirect off https to $next")
            return next.toString()
        }

        private fun parseUri(text: String): URI = try {
            URI(text)
        } catch (e: URISyntaxException) {
            throw IOException("malformed URL: $text", e)
        }

        private const val MAX_REDIRECTS = 5
        private val SUCCESS_STATUS = 200..299
        private val REDIRECT_STATUS = 300..399

        /**
         * GitHub's 404 for a release asset: nothing is published on the channel yet, or the snapshot is mid-replace.
         */
        private class NotPublished : IOException("not published")

        private inline fun <T> HttpURLConnection.useResponse(block: (HttpURLConnection) -> T): T = try {
            block(this)
        } finally {
            disconnect()
        }

        /** `InputStream.readNBytes(int)` is API 33 on Android. */
        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (out.size() < limit) {
                val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (read == -1) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}

/** Reports each new whole percent of a download of [total] bytes, or null once when the length is unknown. */
private class DownloadProgress(private val total: Long?, private val onProgress: (Int?) -> Unit) {
    private var received = 0L
    private var shown = -1

    init {
        onProgress(if (total == null) null else 0)
    }

    fun add(bytes: Int) {
        received += bytes
        val percent = total?.let { (received * FULL_PERCENT / it).toInt().coerceAtMost(FULL_PERCENT) }
        if (percent != null && percent != shown) {
            shown = percent
            onProgress(percent)
        }
    }

    /** A response that gave its length must have delivered all of it. */
    fun checkComplete() {
        if (total != null && received != total) throw IOException("got $received of $total bytes")
    }
}
