package com.aiproject.musicplayer.ui

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.aiproject.musicplayer.playback.OutputDevice

data class OutputInfo(val device: OutputDevice?, val bluetoothCodec: String)

/** Tracks the external audio output (USB DAC, Bluetooth, wired) while composed. */
@Composable
fun rememberOutputInfo(): OutputInfo {
    val context = LocalContext.current
    if (LocalInspectionMode.current) return OutputInfo(null, "")
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var device by remember { mutableStateOf(OutputDevice.detect(audioManager)) }
    var codec by remember { mutableStateOf("") }
    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { device = OutputDevice.detect(audioManager) }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { device = OutputDevice.detect(audioManager) }
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
    LaunchedEffect(device) {
        codec = ""
        if (device?.kind == OutputDevice.Kind.BLUETOOTH) {
            OutputDevice.fetchBluetoothCodec(context) { codec = it }
        }
    }
    return OutputInfo(device, codec)
}
