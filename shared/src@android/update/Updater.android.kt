package org.meshtastic.aether.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.meshtastic.aether.AndroidAppContext
import org.meshtastic.aether.log.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val TAG = "update"

/** Installers of record that keep the app up to date themselves; an install from one of them never updates in-app. */
internal val STORE_INSTALLERS = setOf("com.android.vending", "org.fdroid.fdroid", "com.amazon.venezia")

private val lock = Any()
private var shared: Updater? = null

internal actual fun createUpdater(): Updater? {
    val url = manifestOverride() ?: manifestUrl(BuildInfo.CHANNEL) ?: return null
    val app = AndroidAppContext.context?.applicationContext ?: return null
    if (installerOf(app) in STORE_INSTALLERS) return null
    return synchronized(lock) { shared ?: AndroidUpdater(app, url).also { shared = it } }
}

private fun installerOf(context: Context): String? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(context.packageName)
    }
} catch (_: PackageManager.NameNotFoundException) {
    null
}

/**
 * Checks the downloaded package's name, signing key and version code against this app, then commits it through a
 * [PackageInstaller] session. The system asks the person to confirm, and the first time to let Aether install apps;
 * on Android 12 and later an update to an app that installed itself may skip the confirmation.
 */
private class AndroidUpdater(private val app: Context, manifestUrl: String) :
    HttpUpdater(manifestUrl, BuildInfo.CODE, assetsBesideManifest = manifestOverride() != null) {
    override fun modeFor(latest: PublishedBuild): InstallMode = if (assetFor(latest) != null) InstallMode.INSTALL else InstallMode.OPEN_PAGE

    override fun assetFor(latest: PublishedBuild): Asset? = latest.assets["android"]

    override fun downloadTarget(asset: Asset): File = File(app.cacheDir, "update.apk")

    override suspend fun installFile(file: File, latest: PublishedBuild): InstallState? {
        try {
            val packageManager = app.packageManager
            val archive = packageManager.archiveInfo(file.path)
            val installed = packageManager.installedInfo(app.packageName)
            val failure = packageVerdict(
                packageName = archive?.packageName,
                appPackage = app.packageName,
                versionCode = archive?.longCode() ?: 0,
                installedVersionCode = installed?.longCode() ?: 0,
                sameKey = archive?.let { sameSigners(it, installed) },
            )
            if (failure != null) {
                Log.w(TAG, "update not installed: $failure")
                return InstallState.Failed(failure)
            }
            // Before the commit, since the receiver can hear back before it returns.
            _install.value = InstallState.Installing
            commit(file)
            return null
        } catch (e: IOException) {
            Log.w(TAG, "install failed: ${e.message}")
            return InstallState.Failed(InstallFailure.INSTALLER, e.message)
        } catch (e: RuntimeException) {
            // The package manager and installer throw SecurityException, IllegalStateException and kin; none may crash the app.
            Log.w(TAG, "install failed: ${e.message}")
            return InstallState.Failed(InstallFailure.INSTALLER, e.message)
        } finally {
            file.delete()
        }
    }

    override fun browse(url: String) {
        try {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            // A TV may have no browser.
            Log.w(TAG, "could not open $url: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longCode(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    /** Whether the package's signing certificates are the installed app's, or null when either cannot be read. */
    /**
     * Whether the package may replace this install: its signers, with the past keys a rotated key vouches for, cover
     * the installed app's current signers. Null when either side's signers cannot be read.
     */
    @Suppress("DEPRECATION")
    private fun sameSigners(archive: PackageInfo, installed: PackageInfo?): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val theirs = digests(archive.signatures) ?: return null
            val ours = digests(installed?.signatures) ?: return null
            return theirs == ours
        }
        val incoming = archive.signingInfo ?: return null
        val ours = digests(installed?.signingInfo?.apkContentsSigners) ?: return null
        val history = if (incoming.hasMultipleSigners()) emptySet() else digests(incoming.signingCertificateHistory).orEmpty()
        val theirs = (digests(incoming.apkContentsSigners) ?: return null) + history
        return theirs.containsAll(ours)
    }

    private fun digests(signatures: Array<android.content.pm.Signature>?): Set<String>? =
        signatures?.takeIf { it.isNotEmpty() }?.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }?.toSet()

    private fun PackageManager.archiveInfo(path: String): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getPackageArchiveInfo(path, flags)
        }
    }

    private fun PackageManager.installedInfo(packageName: String): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                getPackageInfo(packageName, flags)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun commit(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(statusSender())
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        }
    }

    /** The installer fills the intent's extras, so the pending intent is mutable; it names the receiver outright. */
    private fun statusSender(): IntentSender {
        val intent = Intent(app, UpdateStatusReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(app, 0, intent, flags).intentSender
    }

    fun onStatus(state: InstallState) {
        _install.value = state
    }
}

/**
 * Hears the installer's verdict. Declared in the app manifest, not exported, so only the system's pending intent
 * reaches it; it works even when the process restarted since the commit, and then only the confirmation matters.
 */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = synchronized(lock) { shared } as? AndroidUpdater
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // The system's confirmation, and the one-time "install unknown apps" grant before it when needed.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                try {
                    checkNotNull(confirm) { "no confirmation intent" }
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    // Backing out of the prompt may send no status at all; Install must work again then.
                    updater?.onStatus(InstallState.Idle)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "confirmation did not open: ${e.message}")
                    updater?.onStatus(InstallState.Failed(InstallFailure.INSTALLER, e.message))
                }
            }
            // Installing this app ends its process first, so success is seldom heard.
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> updater?.onStatus(InstallState.Idle)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "install failed with status $status: $message")
                updater?.onStatus(InstallState.Failed(InstallFailure.INSTALLER, message))
            }
        }
    }
}
