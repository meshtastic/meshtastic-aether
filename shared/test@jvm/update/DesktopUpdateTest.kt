package org.meshtastic.aether.update

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DesktopUpdateTest {
    private val dir: File = Files.createTempDirectory("aether-update").toFile()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun serve(path: String, status: Int, body: ByteArray, declaredLength: Long = body.size.toLong()) {
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else declaredLength)
            if (body.isNotEmpty()) runCatching { exchange.responseBody.use { it.write(body) } }
            exchange.close()
        }
    }

    // Regression: `./kotlin run` has a class path of many entries; taking the first would overwrite a library jar.
    @Test
    fun `only a lone executable jar in a writable folder is replaced in place`() {
        val jar = File(dir, "aether.jar").apply { writeText("x") }
        val lib = File(dir, "lib.jar").apply { writeText("x") }
        assertEquals(jar.absoluteFile, replaceableJar(jar.path, ":") { true })
        assertNull(replaceableJar("${jar.path}:${lib.path}", ":") { true })
        assertNull(replaceableJar(dir.path, ":") { true })
        assertNull(replaceableJar(jar.path, ":") { false })
    }

    // Regression: the restarted app would read the pid after the flag as a launch argument.
    @Test
    fun `the restart flag and its pid are stripped and the rest kept in order`() {
        val args = arrayOf("--tcp", "mesh.local", AFTER_UPDATE_FLAG, "999999999", "--fullscreen")
        assertEquals(listOf("--tcp", "mesh.local", "--fullscreen"), DesktopLaunch.afterUpdate(args))
        assertEquals(listOf("--demo"), DesktopLaunch.afterUpdate(arrayOf("--demo")))
    }

    // Regression: an Intel Mac or Linux on arm handed the arm64 or x64 jar would not start after the swap.
    @Test
    fun `each OS and CPU gets its own jar and the rest get none`() {
        assertEquals("desktop-linux-x64", desktopAssetKey("Linux", "amd64"))
        assertEquals("desktop-macos-arm64", desktopAssetKey("Mac OS X", "aarch64"))
        assertNull(desktopAssetKey("Mac OS X", "x86_64"))
        assertNull(desktopAssetKey("Linux", "aarch64"))
        assertNull(desktopAssetKey("Windows 11", "amd64"))
    }

    // The digest is the SHA-256 test vector for "abc" (FIPS 180-2, appendix B.1).
    @Test
    fun `a download returns the SHA-256 of what arrived`() = runBlocking {
        serve("/abc", 200, "abc".encodeToByteArray())
        server.start()
        val sha = HttpUpdater.download("$base/abc", File(dir, "f")) {}
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha)
    }

    private class TestUpdater(url: String, code: Long) : HttpUpdater(url, code) {
        override fun modeFor(latest: PublishedBuild) = InstallMode.OPEN_PAGE
        override fun assetFor(latest: PublishedBuild): Asset? = null
        override fun downloadTarget(asset: Asset) = error("unused")
        override suspend fun installFile(file: File, latest: PublishedBuild): InstallState? = null
        override fun browse(url: String) = Unit
    }

    private fun answer(updater: Updater): UpdateState = runBlocking {
        withTimeout(10_000) { updater.check() }.also { assertEquals(it, updater.update.value) }
    }

    // Regression: GitHub's 404 before the first release, or mid-replace, would show as a failure on every device.
    @Test
    fun `a missing manifest means nothing is published and a served one is compared`() {
        serve("/missing", 404, ByteArray(0))
        serve("/manifest", 200, "version=0.2.0\ncode=60\ntag=v0.2.0\n".encodeToByteArray())
        server.start()
        assertEquals(UpdateState.NothingPublished, answer(TestUpdater("$base/missing", 57)))
        val available = assertIs<UpdateState.Available>(answer(TestUpdater("$base/manifest", 57)))
        assertEquals(60L, available.latest.code)
        assertIs<UpdateState.UpToDate>(answer(TestUpdater("$base/manifest", 60)))
    }
}
