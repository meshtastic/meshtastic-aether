package org.meshtastic.aether.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.meshtastic.aether.log.Log
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

private const val TAG = "update"

/** Passed to the restarted app with this process's pid, so it waits for the old one to let go of the serial port and audio. */
const val AFTER_UPDATE_FLAG = "--after-update"
private const val AFTER_UPDATE_WAIT_SECONDS = 15L

/** The platform key of this OS's desktop jar in the manifest, or null where no jar is published for it. */
internal fun desktopAssetKey(osName: String, osArch: String): String? {
    val os = osName.lowercase()
    val arch = osArch.lowercase()
    return when {
        "linux" in os && (arch == "amd64" || arch == "x86_64") -> "desktop-linux-x64"
        "mac" in os && (arch == "aarch64" || arch == "arm64") -> "desktop-macos-arm64"
        else -> null
    }
}

/**
 * The executable jar this process runs from, when it can be replaced in place: the class path is that one `.jar` (the
 * Spring Boot launcher's outer jar; nested classes report `jar:nested:` sources) in a directory this user may write.
 * Null for `./kotlin run`, whose class path is many entries, a read-only install, or an app installed from a deb, dmg
 * or msi ([installed]), whose jar belongs to the package; those get the download page.
 */
internal fun replaceableJar(classPath: String, pathSeparator: String, installed: Boolean = false, canWrite: (File) -> Boolean): File? {
    if (installed) return null
    val entries = classPath.split(pathSeparator).filter { it.isNotEmpty() }
    val jar = entries.singleOrNull()?.takeIf { it.endsWith(".jar") }?.let { File(it).absoluteFile } ?: return null
    return jar.takeIf { it.isFile && jar.parentFile?.let(canWrite) == true }
}

/** The arguments `main` got, so a restart after an update reopens the same way. */
object DesktopLaunch {
    var args: List<String> = emptyList()

    /**
     * Strips [AFTER_UPDATE_FLAG] and its pid from [args] and waits up to 15 s for that process to exit, so the
     * restarted app never races the old one for the saved serial port. Returns the remaining arguments.
     */
    fun afterUpdate(args: Array<String>): List<String> {
        val at = args.indexOf(AFTER_UPDATE_FLAG)
        if (at < 0) return args.toList()
        args.getOrNull(at + 1)?.toLongOrNull()?.let { pid ->
            ProcessHandle.of(pid).ifPresent { old ->
                try {
                    old.onExit().get(AFTER_UPDATE_WAIT_SECONDS, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    Log.w(TAG, "the previous process $pid did not exit in time: ${e.message}")
                }
            }
        }
        return args.toList().filterIndexed { i, _ -> i != at && i != at + 1 }
    }
}

private val lock = Any()
private var shared: Updater? = null

internal actual fun createUpdater(): Updater? {
    val url = manifestOverride() ?: manifestUrl(BuildInfo.CHANNEL) ?: return null
    return synchronized(lock) { shared ?: DesktopUpdater(url).also { shared = it } }
}

/**
 * Downloads this OS's jar next to the running one, keeps the old one as `<jar>.bak`, renames the new one into place
 * and starts it with the same Java, JVM options and arguments, then halts. Renames leave the running JVM's open jar
 * readable on Linux and macOS; past the rename only JDK classes run, and halt skips shutdown hooks that could load
 * app classes from a file that is no longer the one this process opened.
 */
private class DesktopUpdater(manifestUrl: String) :
    HttpUpdater(manifestUrl, BuildInfo.CODE, assetsBesideManifest = manifestOverride() != null) {
    private val assetKey = desktopAssetKey(System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty())
    private val jar: File? = replaceableJar(
        System.getProperty("java.class.path").orEmpty(),
        File.pathSeparator,
        installed = System.getProperty("jpackage.app-path") != null,
    ) { it.canWrite() }

    override fun modeFor(latest: PublishedBuild): InstallMode =
        if (jar != null && assetFor(latest) != null) InstallMode.INSTALL_AND_RESTART else InstallMode.OPEN_PAGE

    override fun assetFor(latest: PublishedBuild): Asset? = assetKey?.let { latest.assets[it] }

    override fun downloadTarget(asset: Asset): File = File(checkNotNull(jar).parentFile, ".${jar.name}.update")

    override suspend fun installFile(file: File, latest: PublishedBuild): InstallState? = withContext(Dispatchers.IO) {
        val target = checkNotNull(jar)
        val backup = File(target.parentFile, "${target.name}.bak")
        _install.value = InstallState.Installing
        try {
            val java = ProcessHandle.current().info().command().orElse(null) ?: throw IOException("cannot find the running java")
            val jvmOptions = ManagementFactory.getRuntimeMXBean().inputArguments
            val command = listOf(java) + jvmOptions + listOf("-jar", target.absolutePath) +
                DesktopLaunch.args + listOf(AFTER_UPDATE_FLAG, ProcessHandle.current().pid().toString())
            val restart = ProcessBuilder(command).inheritIO()
            Log.i(TAG, "installing ${latest.version}; restarting")
            Files.move(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            try {
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(backup.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                throw e
            }
            try {
                restart.start()
            } catch (e: IOException) {
                // This process keeps running from the old jar, so put it back where it was.
                Files.move(backup.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                throw e
            }
            Runtime.getRuntime().halt(0)
            null
        } catch (e: IOException) {
            file.delete()
            Log.w(TAG, "could not swap in ${latest.version}: ${e.message}")
            InstallState.Failed(InstallFailure.INSTALLER, e.message)
        } catch (e: SecurityException) {
            file.delete()
            InstallState.Failed(InstallFailure.INSTALLER, e.message)
        }
    }

    override fun browse(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            } else {
                Log.i(TAG, "get the update at $url")
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not open $url: ${e.message}")
        }
    }
}
