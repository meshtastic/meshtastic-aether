package org.meshtastic.aether.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import org.meshtastic.aether.AndroidAppContext
import org.meshtastic.aether.log.Log
import org.meshtastic.sdk.RadioTransport
import org.meshtastic.sdk.transport.serial.AndroidSerialPorts
import org.meshtastic.sdk.transport.serial.isLikelyMeshtastic
import kotlin.coroutines.resume

private const val ACTION_USB_PERMISSION = "org.meshtastic.aether.USB_PERMISSION"

@Volatile private var initialized = false

private fun context(): Context? = AndroidAppContext.context?.also {
    if (!initialized) {
        AndroidSerialPorts.init(it)
        initialized = true
    }
}

internal actual fun serialSupported(): Boolean = true

internal actual suspend fun listSerialPorts(): List<SerialRadio> {
    val ctx = context() ?: return emptyList()
    val manager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    // jSerialComm only lists a device once the app holds permission for it, so ask for each one first.
    for (device in AndroidSerialPorts.usbDevices(ctx)) {
        if (!manager.hasPermission(device) && !requestPermission(ctx, manager, device)) {
            Log.w("serial", "USB permission refused for ${device.deviceName}")
        }
    }
    return AndroidSerialPorts.list().map { info ->
        SerialRadio(info.name, info.usbProduct ?: info.description ?: info.name, info.isLikelyMeshtastic())
    }
}

/** Shows the system's USB permission dialog for [device] and returns whether it was granted. */
private suspend fun requestPermission(context: Context, manager: UsbManager, device: UsbDevice): Boolean =
    suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                runCatching { context.unregisterReceiver(this) }
                if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        // The system fills in the grant extras, so the intent must be mutable; naming the package makes it explicit.
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        manager.requestPermission(device, PendingIntent.getBroadcast(context, device.deviceId, intent, flags))
    }

internal actual fun createSerialTransport(path: String): RadioTransport? {
    context()
    return AndroidSerialPorts.open(path)
}
