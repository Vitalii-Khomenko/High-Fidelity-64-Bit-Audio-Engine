package com.aiproject.musicplayer.playback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import com.aiproject.musicplayer.DirectOutputPolicy

/**
 * Bit-perfect USB output through Android 14's mixer attributes
 * (docs/DIRECT_OUTPUT_PLAN.md, phase 1).
 *
 * The engine asks [encodingFor] before every stream open. When enabled and a
 * USB DAC offers a bit-perfect format at exactly that rate, this sets it as
 * the preferred mixer attributes for media and returns the encoding the
 * stream must use; otherwise it clears the preference and returns -1 (shared
 * mixer). Called from engine threads while the output is locked: it must
 * never call back into the engine.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class BitPerfectOutput(private val audioManager: AudioManager) : DirectOutputPolicy {

    @Volatile var enabled = false

    // The device whose preference we set, so it can be released.
    private var preferredOn: AudioDeviceInfo? = null

    /** The connected USB output that can play bit-perfect, if any. */
    fun capableDevice(): AudioDeviceInfo? = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            .firstOrNull { candidates(it).isNotEmpty() }
    }.getOrNull()

    @Synchronized
    override fun encodingFor(sampleRate: Int, channels: Int): Int {
        if (!enabled) return release()
        return try {
            val device = capableDevice() ?: return release()
            val choice = DirectFormatChoice.choose(candidates(device), sampleRate, channels) ?: return release()
            if (preferredOn != null && preferredOn?.id != device.id) release()
            if (!audioManager.setPreferredMixerAttributes(MEDIA, device, choice.handle)) return release()
            preferredOn = device
            choice.encoding.id
        } catch (_: Exception) {
            // OEM builds may throw for devices that went away meanwhile.
            release()
        }
    }

    /** Hands the DAC back to the system mixer. Returns -1 for convenience. */
    @Synchronized
    fun release(): Int {
        preferredOn?.let { device -> runCatching { audioManager.clearPreferredMixerAttributes(MEDIA, device) } }
        preferredOn = null
        return -1
    }

    private fun candidates(device: AudioDeviceInfo): List<DirectCandidate<AudioMixerAttributes>> =
        audioManager.getSupportedMixerAttributes(device)
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            .mapNotNull { attributes ->
                val format = attributes.format
                val encoding = when (format.encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> OutputEncoding.PCM_16
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> OutputEncoding.PCM_24
                    AudioFormat.ENCODING_PCM_32BIT -> OutputEncoding.PCM_32
                    AudioFormat.ENCODING_PCM_FLOAT -> OutputEncoding.FLOAT
                    else -> return@mapNotNull null
                }
                DirectCandidate(format.sampleRate, format.channelCount, encoding, attributes)
            }

    private companion object {
        val MEDIA: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
