package org.meshtastic.aether

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.meshtastic.aether.log.Log
import org.meshtastic.aether.midi.midiOutputNames
import org.meshtastic.aether.ui.AppCommands
import org.meshtastic.aether.ui.parseLaunchArgs
import org.meshtastic.aether.update.DesktopLaunch
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Point
import java.awt.Taskbar
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.IOException
import javax.imageio.ImageIO

private const val ICON_RESOURCE = "icon.png"
private const val ICON_TAG = "icon"

private const val CURSOR_SIZE = 16

private val INITIAL_SIZE = DpSize(1000.dp, 700.dp)
private const val MIN_WIDTH = 640
private const val MIN_HEIGHT = 480

private val isMac = "mac" in System.getProperty("os.name").orEmpty().lowercase()

/** A fully transparent cursor: the app shows it after a few idle seconds so a projector shows no pointer. */
private fun blankCursor(): Cursor = Toolkit.getDefaultToolkit().createCustomCursor(
    BufferedImage(CURSOR_SIZE, CURSOR_SIZE, BufferedImage.TYPE_INT_ARGB),
    Point(0, 0),
    "blank",
)

/** The app icon from the module's resources, or null with a log line when it is missing or unreadable. */
private fun loadIcon(): BufferedImage? = try {
    Thread.currentThread().contextClassLoader.getResourceAsStream(ICON_RESOURCE)?.use { ImageIO.read(it) }
        ?: run {
            Log.w(ICON_TAG, "resource $ICON_RESOURCE not found; the default window icon stays")
            null
        }
} catch (e: IOException) {
    Log.w(ICON_TAG, "could not read $ICON_RESOURCE", e)
    null
}

/**
 * On macOS the dock shows the process icon, which a window icon does not set. `Taskbar` refuses with
 * `UnsupportedOperationException` (headless included) or `SecurityException`.
 */
private fun setDockIcon(icon: BufferedImage) {
    try {
        if (isMac && Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)
        ) {
            Taskbar.getTaskbar().iconImage = icon
        }
    } catch (e: UnsupportedOperationException) {
        Log.w(ICON_TAG, "could not set the dock icon", e)
    } catch (e: SecurityException) {
        Log.w(ICON_TAG, "could not set the dock icon", e)
    }
}

private const val APP_NAME = "Aether"

fun main(args: Array<String>) {
    // macOS names the menu bar's app menu and the Dock entry from this, read once when AWT starts; without it they
    // show the JVM class.
    System.setProperty("apple.awt.application.name", APP_NAME)
    if ("--midi-list" in args) {
        midiOutputNames().forEach(::println)
        return
    }
    val appArgs = DesktopLaunch.afterUpdate(args)
    DesktopLaunch.args = appArgs
    val launch = parseLaunchArgs(appArgs.toTypedArray())
    KeepAwake.start()
    val icon = loadIcon()
    icon?.let(::setDockIcon)
    val windowIcon = icon?.let { BitmapPainter(it.toComposeImageBitmap()) }
    application {
        val state = rememberWindowState(
            placement = if (launch.fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating,
            size = INITIAL_SIZE,
        )
        val hidden = remember { PointerIcon(blankCursor()) }
        val toggleFullscreen = {
            val fullscreen = state.placement == WindowPlacement.Fullscreen
            state.placement = if (fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
        }
        val commands = remember { AppCommands() }
        Window(
            onCloseRequest = {
                KeepAwake.stop()
                exitApplication()
            },
            title = APP_NAME,
            icon = windowIcon,
            state = state,
        ) {
            window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
            if (isMac) MacMenuBar(state, commands, toggleFullscreen)
            App(
                launch = LaunchOptions(
                    link = launch.link,
                    startupError = launch.error,
                    look = launch.look,
                    launchedFullscreen = launch.fullscreen,
                    ask = launch.ask,
                    midi = launch.midi,
                ),
                host = HostWindow(
                    onToggleFullscreen = toggleFullscreen,
                    isFullscreen = state.placement == WindowPlacement.Fullscreen,
                    hiddenCursor = hidden,
                    commands = commands,
                ),
            )
        }
    }
}

/** The macOS menu bar: the host menus call `AppCommands`, like Back and the keys do. */
@Composable
private fun FrameWindowScope.MacMenuBar(state: WindowState, commands: AppCommands, toggleFullscreen: () -> Unit) {
    MenuBar {
        Menu("File") {
            Item("Disconnect", enabled = commands.sessionRunning, onClick = commands::disconnect)
        }
        Menu("View") {
            Item(
                if (state.placement == WindowPlacement.Fullscreen) "Exit Full Screen" else "Enter Full Screen",
                shortcut = KeyShortcut(Key.F, ctrl = true, meta = true),
                onClick = toggleFullscreen,
            )
            Item(
                if (commands.muted) "Unmute" else "Mute",
                enabled = commands.sessionRunning,
                onClick = commands::toggleMute,
            )
        }
        Menu("Window") {
            Item("Minimize", shortcut = KeyShortcut(Key.M, meta = true), onClick = { state.isMinimized = true })
        }
    }
}
