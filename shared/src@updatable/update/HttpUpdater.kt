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
import java.security.MessageDigest

private const val TAG = "update"
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000
private const val MAX_MANIFEST_BYTES = 64 * 1024

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
    protected val _install = MutableStateFlow<InstallState>(InstallState.Idle)
    override val install: StateFlow<InstallState> = _install.asStateFlow()
    @Volatile private var currentManifest = initialManifest
    private val checkLock = Mutex()
    private var installing: Job? = null

    override fun follow(track: UpdateTrack) {
        // A test manifest is the one being tested; a track has no say over it.
        if (assetsBesideManifest) return
        currentManifest = manifestFor(track) ?: return
        _update.value = UpdateState.Idle
    }

    override suspend fun check(): UpdateState = checkLock.withLock {
        val manifest = currentManifest
        val previous = _update.value
        // An offered update stays offered while the next check runs, so the Update button does not blink.
        if (previous !is UpdateState.Available) _update.value = UpdateState.Checking
        val answer = try {
            fetchLatest(manifest)?.let { latest ->
                Log.i(TAG, "running $runningCode, published ${latest.version} (${latest.code})")
                compare(runningCode, latest)
            } ?: UpdateState.Failed
        } catch (e: CancellationException) {
            if (manifest == currentManifest) _update.value = previous
            throw e
        } catch (_: NotPublished) {
            // The snapshot's manifest is briefly missing while it is replaced; an offer already made stands.
            if (previous is UpdateState.Available) previous else UpdateState.NothingPublished
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.message}")
            if (previous is UpdateState.Available) previous else UpdateState.Failed
        }
        // The track changed while this ran: the answer belongs to the old one, and follow() already reset the state.
        if (manifest != currentManifest) return@withLock _update.value
        _update.value = answer
        answer
    }

    override fun install(latest: PublishedBuild) {
        if (installing?.isActive == true || _install.value == InstallState.Installing) return
        if (assetFor(latest) == null || modeFor(latest) == InstallMode.OPEN_PAGE) {
            browse(latest.pageUrl)
            return
        }
        installing = scope.launch {
            _install.value = InstallState.Downloading(null)
            try {
                // An offer can be a day old and a snapshot's files are pruned on every push, so install what is published now.
                val current = when (val fresh = check()) {
                    is UpdateState.Available -> fresh.latest
                    is UpdateState.UpToDate -> {
                        _install.value = InstallState.Idle
                        return@launch
                    }
                    else -> latest
                }
                val asset = assetFor(current) ?: run {
                    _install.value = InstallState.Idle
                    browse(current.pageUrl)
                    return@launch
                }
                val file = downloadTarget(asset)
                val sha = download(current.downloadUrl(asset), file) { _install.value = InstallState.Downloading(it) }
                if (sha != asset.sha256) {
                    file.delete()
                    Log.w(TAG, "checksum mismatch: got $sha, want ${asset.sha256}")
                    _install.value = InstallState.Failed(InstallFailure.CHECKSUM)
                    return@launch
                }
                installFile(file, current)?.let { _install.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing here may crash the app: URI, file and security errors all end as a failed download.
                Log.w(TAG, "download failed: ${e.message}")
                _install.value = InstallState.Failed(InstallFailure.DOWNLOAD, e.message)
            }
        }
    }

    private suspend fun fetchLatest(manifest: String): PublishedBuild? =
        PublishedBuild.parse(fetchText(manifest))
            ?.let { if (assetsBesideManifest) it.copy(downloadBase = manifest.substringBeforeLast('/')) else it }

    /** The asset this platform installs, or null when the build carries none for it. */
    protected abstract fun assetFor(latest: PublishedBuild): Asset?

    /** Where the download goes; replaced if present. */
    protected abstract fun downloadTarget(asset: Asset): File

    /** Puts the verified [file] in; returns the outcome, or null when the platform reports it later through [_install]. */
    protected abstract suspend fun installFile(file: File, latest: PublishedBuild): InstallState?

    override fun openPage(latest: PublishedBuild) = browse(latest.pageUrl)

    protected abstract fun browse(url: String)

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        open(url).useResponse { connection ->
            val bytes = connection.inputStream.use { it.readNBytesCompat(MAX_MANIFEST_BYTES + 1) }
            if (bytes.size > MAX_MANIFEST_BYTES) throw IOException("manifest over $MAX_MANIFEST_BYTES bytes")
            bytes.decodeToString()
        }
    }

    companion object {
        /**
         * Downloads [url] into [file], replacing it, and returns the SHA-256 of what arrived in lowercase hex.
         * [onProgress] gets each new whole percent, or null when the response gives no length. A failed download
         * throws [IOException] and leaves no file behind.
         */
        suspend fun download(url: String, file: File, onProgress: (Int?) -> Unit): String = withContext(Dispatchers.IO) {
            try {
                open(url).useResponse { connection ->
                    val total = connection.contentLengthLong.takeIf { it > 0 }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var shown = -1
                    onProgress(if (total == null) null else 0)
                    connection.inputStream.use { input ->
                        file.outputStream().use { out ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                received += read
                                val percent = total?.let { (received * 100 / it).toInt().coerceAtMost(100) }
                                if (percent != null && percent != shown) {
                                    shown = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                    if (total != null && received != total) throw IOException("got $received of $total bytes")
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
        }

        /** A GET that follows GitHub's redirect to its download host, which is a different origin. */
        private fun open(url: String): HttpURLConnection {
            var target = url
            repeat(MAX_REDIRECTS) {
                val connection = URI(target).toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", "Aether")
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("HTTP $status without Location")
                    connection.disconnect()
                    val next = URI(target).resolve(location)
                    // Never follow a redirect off HTTPS; a local test server may stay on plain HTTP.
                    if (next.scheme != "https" && URI(target).scheme == "https") throw IOException("redirect off https to $next")
                    target = next.toString()
                    return@repeat
                }
                if (status !in 200..299) {
                    connection.disconnect()
                    throw if (status == 404) NotPublished() else IOException("HTTP $status")
                }
                return connection
            }
            throw IOException("too many redirects")
        }

        private const val MAX_REDIRECTS = 5

        /** GitHub's 404 for a release asset: nothing is published on the channel yet, or the snapshot is mid-replace. */
        private class NotPublished : IOException("not published")

        private inline fun <T> HttpURLConnection.useResponse(block: (HttpURLConnection) -> T): T = try {
            block(this)
        } finally {
            disconnect()
        }

        /** `InputStream.readNBytes(int)` is API 33 on Android. */
        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (out.size() < limit) {
                val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (read == -1) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
