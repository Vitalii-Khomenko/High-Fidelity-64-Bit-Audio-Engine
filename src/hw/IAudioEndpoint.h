#pragma once

#include <vector>
#include <string>
#include <memory>
#include <cstdint>
#include "../core/RingBuffer.h"

namespace audio_engine {
namespace hw {

/**
 * @brief Abstract interface for Hardware Audio Outputs.
 * 
 * Defines strictly pure-virtual functions to decouple business logic from OS hardware access (ALSA, WASAPI, CoreAudio).
 */
class IAudioEndpoint {
public:
    virtual ~IAudioEndpoint() = default;

    /**
     * @brief Initialize hardware with exclusive access to the DAC parameters.
     * @param deviceName The hardware endpoint identifier (ALSA name like "hw:0,0").
     * @param sampleRate Requested Sample Rate (Follow Source Frequency requirement).
     * @param numChannels Number of channels.
     * @param bitDepth Desired integer bit depth (16, 24, 32).
     */
    virtual bool initialize(const std::string& deviceName, uint32_t sampleRate, size_t numChannels, uint8_t bitDepth) = 0;

    /**
     * @brief Stop playback and tear down lock-free buffers.
     */
    virtual void terminate() = 0;

    /**
     * @brief Push processed audio blocks to the DAC. Lock-free ring buffers must be utilized behind the scenes.
     */
    virtual void startHardwareThread() = 0;
};

/**
 * @brief Dummy implementation targeting Android ALSA bypassing standard AudioTrack mixers.
 */
class ALSAEndpoint : public IAudioEndpoint {
public:
    ALSAEndpoint() : m_running(false) {
        // Here we would allocate ALSA handle pointers (snd_pcm_t*)
    }

    ~ALSAEndpoint() override {
        terminate();
    }

    bool initialize(const std::string& deviceName, uint32_t sampleRate, size_t numChannels, uint8_t bitDepth) override {
        // Dummy logic
        m_deviceName = deviceName;
        m_sampleRate = sampleRate;
        m_numChannels = numChannels;
        m_bitDepth = bitDepth;

        // In production, we'd open device with SND_PCM_STREAM_PLAYBACK, request format SND_PCM_FORMAT_S32_LE (or DSD DOP),
        // and configure snd_pcm_hw_params_set_access(..., SND_PCM_ACCESS_RW_INTERLEAVED).
        return true;
    }

    void terminate() override {
        if (m_running) {
            m_running = false;
            // Wait for thread to close, release ALSA handles snd_pcm_close(...)
        }
    }

    void startHardwareThread() override {
        m_running = true;
        // Spin up std::thread utilizing POSIX priority scheduling (SCHED_FIFO) for real-time priority class.
    }

private:
    std::string m_deviceName;
    uint32_t m_sampleRate = 44100;
    size_t m_numChannels = 2;
    uint8_t m_bitDepth = 16;
    bool m_running;

    // ALSA references:
    // snd_pcm_t *m_pcmHandle;
};

} // namespace hw
} // namespace audio_engine
