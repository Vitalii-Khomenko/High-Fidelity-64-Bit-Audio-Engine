package com.aiproject.musicplayer.playback

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build

/** The external output the system most likely routes media to. */
data class OutputDevice(
    val name: String,
    val kind: Kind,
    val maxSampleRate: Int,
    val maxBits: Int,
) {
    enum class Kind(val label: String) { USB("USB"), BLUETOOTH("Bluetooth"), WIRED("Wired") }

    val summary: String
        get() = buildList {
            add(kind.label)
            if (maxSampleRate > 0) add(FormatText.rate(maxSampleRate))
            if (maxBits > 0) add("$maxBits-bit")
        }.joinToString(" · ")

    companion object {
        fun detect(audioManager: AudioManager): OutputDevice? = try {
            detectOrThrow(audioManager)
        } catch (_: Exception) {
            // Some OEM builds throw (or return null) without Bluetooth permission.
            null
        }

        private fun detectOrThrow(audioManager: AudioManager): OutputDevice? {
            val outputs: Array<AudioDeviceInfo> = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return null
            val priority = buildList {
                add(AudioDeviceInfo.TYPE_USB_DEVICE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(AudioDeviceInfo.TYPE_USB_HEADSET)
                add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
                add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            }
            val device = priority.firstNotNullOfOrNull { type -> outputs.firstOrNull { it.type == type } } ?: return null
            val kind = when (device.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE -> Kind.USB
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> Kind.WIRED
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && device.type == AudioDeviceInfo.TYPE_USB_HEADSET) Kind.USB else Kind.BLUETOOTH
            }
            val bits = device.encodings.maxOfOrNull { encoding ->
                when (encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> 16
                    AudioFormat.ENCODING_PCM_8BIT -> 8
                    AudioFormat.ENCODING_PCM_FLOAT -> 32
                    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) when (encoding) {
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                        AudioFormat.ENCODING_PCM_32BIT -> 32
                        else -> 0
                    } else 0
                }
            } ?: 0
            val name = device.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: kind.label
            return OutputDevice(name, kind, device.sampleRates.maxOrNull() ?: 0, bits)
        }

        /**
         * Active A2DP codec (SBC, AAC, aptX, LDAC, ...) via the hidden
         * BluetoothA2dp.getCodecStatus. Best effort: empty when unavailable.
         */
        @SuppressLint("MissingPermission")
        fun fetchBluetoothCodec(context: Context, onResult: (String) -> Unit) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) return
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            if (!adapter.isEnabled) return
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val codec = try {
                        val a2dp = proxy as BluetoothA2dp
                        val device = a2dp.connectedDevices.firstOrNull()
                        if (device == null) "" else {
                            val status = BluetoothA2dp::class.java.getMethod("getCodecStatus", device.javaClass).invoke(a2dp, device)
                            val config = status?.javaClass?.getMethod("getCodecConfig")?.invoke(status)
                            when (config?.javaClass?.getMethod("getCodecType")?.invoke(config) as? Int) {
                                0 -> "SBC"; 1 -> "AAC"; 2 -> "aptX"; 3 -> "aptX HD"; 4 -> "LDAC"
                                5 -> "aptX Adaptive"; 6 -> "LC3"; else -> ""
                            }
                        }
                    } catch (_: Exception) {
                        ""
                    }
                    adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                    onResult(codec)
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            }, BluetoothProfile.A2DP)
        }
    }
}
