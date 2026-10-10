package com.aiproject.musicplayer.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aiproject.musicplayer.AudioEngine
import java.util.concurrent.Executor

/** What the own USB driver is doing, for the settings screen. */
sealed class UsbDriverState {
    data object Off : UsbDriverState()
    data object NoDevice : UsbDriverState()
    data class WaitingForPermission(val name: String) : UsbDriverState()
    data class Denied(val name: String) : UsbDriverState()
    /** The device has no playback format the driver can use. */
    data class Unsupported(val name: String) : UsbDriverState()
    data class Active(val name: String, val info: AudioEngine.UsbDacInfo) : UsbDriverState()
}

/**
 * Phase 2 of docs/DIRECT_OUTPUT_PLAN.md on the app side: finds a USB Audio
 * Class DAC, asks for permission, opens it and hands its file descriptor and
 * descriptors to the engine's own driver (src/usb). Android loses the DAC
 * while the driver holds it; it gets it back when the driver is switched off,
 * the DAC is unplugged or the service ends.
 *
 * All methods run on the main thread; engine calls go through [engineThread]
 * (the service's engine executor), which also orders them before release().
 */
class UsbDacDriver(
    private val context: Context,
    private val engine: AudioEngine,
    private val engineThread: Executor,
    private val onState: (UsbDriverState) -> Unit,
    /** The DAC in use went away (unplugged): pause, as Android does for a lost output. */
    private val onLost: () -> Unit,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var enabled = false
    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    var state: UsbDriverState = UsbDriverState.Off
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val dev = intentDevice(intent)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (connection == null) connect()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (dev != null && dev.deviceName == device?.deviceName) {
                    val wasActive = connection != null
                    release()
                    if (wasActive) onLost()
                    connect()   // another DAC may still be there
                }
                ACTION_PERMISSION -> if (dev != null && dev.deviceName == device?.deviceName && connection == null) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open(dev)
                    else update(UsbDriverState.Denied(label(dev)))
                }
            }
        }
    }

    fun enable() {
        if (enabled) return
        enabled = true
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        connect()
    }

    fun disable() {
        if (!enabled) return
        enabled = false
        runCatching { context.unregisterReceiver(receiver) }
        release()
        update(UsbDriverState.Off)
    }

    /** Asks for the DAC again (e.g. after the permission was denied). */
    fun retry() {
        if (enabled && connection == null) connect()
    }

    private fun connect() {
        if (!enabled) return
        val dev = usbManager.deviceList.values.firstOrNull(::isAudioPlayback)
        device = dev
        when {
            dev == null -> update(UsbDriverState.NoDevice)
            usbManager.hasPermission(dev) -> open(dev)
            else -> {
                update(UsbDriverState.WaitingForPermission(label(dev)))
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
                usbManager.requestPermission(dev, PendingIntent.getBroadcast(context, 0, intent, flags))
            }
        }
    }

    private fun open(dev: UsbDevice) {
        val conn = runCatching { usbManager.openDevice(dev) }.getOrNull()
        val descriptors = conn?.rawDescriptors
        if (conn == null || descriptors == null) {
            conn?.close()
            update(UsbDriverState.Unsupported(label(dev)))
            return
        }
        connection = conn
        val fd = conn.fileDescriptor
        engineThread.execute {
            val info = engine.setUsbDevice(fd, descriptors)
            if (info == null) conn.close()
            ContextCompat.getMainExecutor(context).execute {
                if (connection !== conn) return@execute   // released meanwhile
                if (info == null) {
                    connection = null
                    update(UsbDriverState.Unsupported(label(dev)))
                } else {
                    update(UsbDriverState.Active(label(dev), info))
                }
            }
        }
    }

    /** The engine lets go of the DAC first; then the connection closes (same order on the engine thread). */
    private fun release() {
        val conn = connection ?: return
        connection = null
        engineThread.execute {
            engine.clearUsbDevice()
            conn.close()
        }
    }

    private fun update(newState: UsbDriverState) {
        state = newState
        onState(newState)
    }

    private fun label(dev: UsbDevice): String =
        listOfNotNull(dev.manufacturerName?.trim(), dev.productName?.trim()).filter { it.isNotEmpty() }.distinct()
            .joinToString(" ").ifEmpty { "USB DAC" }

    @Suppress("DEPRECATION")
    private fun intentDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        private const val ACTION_PERMISSION = "com.aiproject.musicplayer.USB_DAC_PERMISSION"

        /** An Audio Class streaming interface with an isochronous OUT endpoint. */
        fun isAudioPlayback(dev: UsbDevice): Boolean = (0 until dev.interfaceCount).any { i ->
            val intf = dev.getInterface(i)
            intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO && intf.interfaceSubclass == 2 &&
                (0 until intf.endpointCount).any { e ->
                    val ep = intf.getEndpoint(e)
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && ep.direction == UsbConstants.USB_DIR_OUT
                }
        }
    }
}
