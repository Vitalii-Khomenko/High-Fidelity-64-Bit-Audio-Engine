#pragma once

#include "IAudioDecoder.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include <unistd.h>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>

namespace audio_engine {
namespace decoders {

/**
 * Formats the phone decodes itself: AAC / HE-AAC (M4A, MP4, ADTS), ALAC,
 * and whatever else MediaExtractor + MediaCodec support (AMR, Opus or Vorbis
 * in WebM/Matroska, ...).
 *
 * Decoders are asked for float output; ones that ignore the request deliver
 * 16-bit (or 24/32-bit) PCM, which is converted exactly. Encoder delay and
 * padding signalled by the container are trimmed so AAC albums stay gapless.
 * Seeks start a little early and discard up to the target, which avoids the
 * MDCT warm-up glitch and makes them sample-exact.
 *
 * Format keys are spelled out (not AMEDIAFORMAT_KEY_*) because several of the
 * constants only exist from API 28/29 and minSdk is 24.
 */
class MediaCodecDecoder : public IAudioDecoder {
public:
    static constexpr int64_t kPrerollUs = 100000;     // decoded and dropped before a seek target
    static constexpr int64_t kStepTimeoutUs = 5000;
    static constexpr int kMaxIdleSteps = 600;         // ~3 s without progress = broken stream

    ~MediaCodecDecoder() override { closeDecoder(); }

    bool openFd(int fd) override {
        closeDecoder();
        m_fd = ::dup(fd);
        if (m_fd < 0) return false;
        const off64_t length = ::lseek64(m_fd, 0, SEEK_END);
        if (length <= 0) return fail();
        m_extractor = AMediaExtractor_new();
        if (!m_extractor || AMediaExtractor_setDataSourceFd(m_extractor, m_fd, 0, length) != AMEDIA_OK) return fail();

        const size_t tracks = AMediaExtractor_getTrackCount(m_extractor);
        AMediaFormat* format = nullptr;
        for (size_t i = 0; i < tracks && !format; ++i) {
            AMediaFormat* f = AMediaExtractor_getTrackFormat(m_extractor, i);
            const char* mime = nullptr;
            if (f && AMediaFormat_getString(f, "mime", &mime) && mime && std::strncmp(mime, "audio/", 6) == 0) {
                m_mime = mime;
                AMediaExtractor_selectTrack(m_extractor, i);
                format = f;
            } else if (f) {
                AMediaFormat_delete(f);
            }
        }
        if (!format) return fail();

        int32_t rate = 0, channels = 0;
        AMediaFormat_getInt32(format, "sample-rate", &rate);
        AMediaFormat_getInt32(format, "channel-count", &channels);
        int64_t durationUs = 0;
        AMediaFormat_getInt64(format, "durationUs", &durationUs);
        int32_t delay = 0, padding = 0;
        AMediaFormat_getInt32(format, "encoder-delay", &delay);
        AMediaFormat_getInt32(format, "encoder-padding", &padding);
        m_bits = sourceBits(format);
        if (rate <= 0 || channels <= 0 || channels > 8) {
            AMediaFormat_delete(format);
            return fail();
        }
        m_rate = static_cast<uint32_t>(rate);
        m_channels = static_cast<size_t>(channels);
        m_delay = std::max(0, delay);
        const int64_t mediaFrames = durationUs > 0 ? llround(static_cast<double>(durationUs) * m_rate / 1e6) : 0;
        const int64_t trimmed = mediaFrames - m_delay - std::max(0, padding);
        m_total = trimmed > 0 ? static_cast<uint64_t>(trimmed) : 0;

        m_codec = AMediaCodec_createDecoderByType(m_mime.c_str());
        if (!m_codec) {
            AMediaFormat_delete(format);
            return fail();
        }
        AMediaFormat_setInt32(format, "pcm-encoding", kEncodingFloat);
        const media_status_t configured = AMediaCodec_configure(m_codec, format, nullptr, nullptr, 0);
        AMediaFormat_delete(format);
        if (configured != AMEDIA_OK || AMediaCodec_start(m_codec) != AMEDIA_OK) return fail();
        m_started = true;
        m_outRate = m_rate;
        m_outChannels = m_channels;
        resetStream(0);

        // Decode the first block now: a codec that cannot handle this stream
        // fails here (open error) instead of in the middle of playback.
        if (!fill()) return fail();
        m_opened = true;
        return true;
    }

    size_t readFrames(core::AudioBuffer& buffer, size_t framesToRead) override {
        if (!m_started || buffer.getNumChannels() < m_channels) return 0;
        framesToRead = std::min(framesToRead, buffer.getNumFrames());
        size_t done = 0;
        while (done < framesToRead) {
            if (m_pcmPos >= m_pcmFrames && !fill()) break;
            size_t n = std::min(framesToRead - done, m_pcmFrames - m_pcmPos);
            if (m_total > 0) n = static_cast<size_t>(std::min<uint64_t>(n, m_total - std::min(m_total, m_position)));
            if (n == 0) break;
            for (size_t ch = 0; ch < m_channels; ++ch) {
                double* dst = buffer.getWritePointer(ch) + done;
                const double* src = m_pcm.data() + m_pcmPos * m_channels + ch;
                for (size_t i = 0; i < n; ++i) dst[i] = src[i * m_channels];
            }
            m_pcmPos += n;
            m_position += n;
            done += n;
        }
        return done;
    }

    bool seekToFrame(uint64_t frame) override {
        if (!m_started) return false;
        if (m_total > 0) frame = std::min(frame, m_total);
        const int64_t mediaFrame = static_cast<int64_t>(frame) + m_delay;
        const int64_t targetUs = static_cast<int64_t>(static_cast<double>(mediaFrame) * 1e6 / m_rate);
        if (AMediaExtractor_seekTo(m_extractor, std::max<int64_t>(0, targetUs - kPrerollUs),
                                   AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC) != AMEDIA_OK) {
            return false;
        }
        if (AMediaCodec_flush(m_codec) != AMEDIA_OK) return false;
        resetStream(frame);
        return true;
    }

    uint32_t getSampleRate() const override { return m_started ? m_rate : 0; }
    size_t getNumChannels() const override { return m_started ? m_channels : 0; }
    uint32_t getBitsPerSample() const override { return m_bits; }
    uint64_t getTotalFrames() const override { return m_total; }
    uint64_t getCurrentFrame() const override { return m_position; }
    Codec getCodec() const override {
        if (m_mime == "audio/mp4a-latm" || m_mime == "audio/aac") return Codec::Aac;
        if (m_mime == "audio/alac") return Codec::Alac;
        if (m_mime == "audio/opus") return Codec::Opus;
        if (m_mime == "audio/vorbis") return Codec::Vorbis;
        if (m_mime == "audio/flac") return Codec::Flac;
        return Codec::Other;
    }

private:
    static constexpr int32_t kEncoding16 = 2;
    static constexpr int32_t kEncodingFloat = 4;
    static constexpr int32_t kEncoding24Packed = 21;
    static constexpr int32_t kEncoding32 = 22;

    /** Bit depth of a lossless source (ALAC keeps it in its magic cookie); 0 for lossy. */
    static uint32_t sourceBits(AMediaFormat* format) {
        int32_t bits = 0;
        if (AMediaFormat_getInt32(format, "bits-per-sample", &bits) && bits > 0) return static_cast<uint32_t>(bits);
        void* data = nullptr;
        size_t size = 0;
        const char* mime = nullptr;
        if (!AMediaFormat_getString(format, "mime", &mime) || !mime || std::strcmp(mime, "audio/alac") != 0) return 0;
        if (!AMediaFormat_getBuffer(format, "csd-0", &data, &size) || !data) return 0;
        const auto* p = static_cast<const uint8_t*>(data);
        // ALACSpecificConfig, optionally wrapped in the 12-byte 'alac' atom header.
        size_t offset = size >= 12 && std::memcmp(p + 4, "alac", 4) == 0 ? 12 : 0;
        return size >= offset + 6 ? p[offset + 5] : 0;
    }

    void resetStream(uint64_t frame) {
        m_position = frame;
        m_discardBefore = static_cast<int64_t>(frame);
        m_pcmFrames = m_pcmPos = 0;
        m_inputDone = m_outputDone = false;
    }

    /**
     * Runs the codec until one block of output is ready in m_pcm. Returns
     * false at the end of the stream or when the codec stops making progress.
     */
    bool fill() {
        m_pcmFrames = m_pcmPos = 0;
        int idle = 0;
        while (!m_outputDone) {
            bool progress = false;
            if (!m_inputDone) {
                const ssize_t in = AMediaCodec_dequeueInputBuffer(m_codec, 0);
                if (in >= 0) {
                    size_t capacity = 0;
                    uint8_t* buf = AMediaCodec_getInputBuffer(m_codec, static_cast<size_t>(in), &capacity);
                    const ssize_t size = buf ? AMediaExtractor_readSampleData(m_extractor, buf, capacity) : -1;
                    if (size < 0) {
                        AMediaCodec_queueInputBuffer(m_codec, static_cast<size_t>(in), 0, 0, 0,
                                                     AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                        m_inputDone = true;
                    } else {
                        const int64_t time = AMediaExtractor_getSampleTime(m_extractor);
                        AMediaCodec_queueInputBuffer(m_codec, static_cast<size_t>(in), 0, static_cast<size_t>(size),
                                                     static_cast<uint64_t>(std::max<int64_t>(0, time)), 0);
                        AMediaExtractor_advance(m_extractor);
                    }
                    progress = true;
                }
            }
            AMediaCodecBufferInfo info{};
            const ssize_t out = AMediaCodec_dequeueOutputBuffer(m_codec, &info, kStepTimeoutUs);
            if (out >= 0) {
                if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) m_outputDone = true;
                size_t size = 0;
                uint8_t* buf = AMediaCodec_getOutputBuffer(m_codec, static_cast<size_t>(out), &size);
                if (buf && info.size > 0 && static_cast<size_t>(info.offset) + static_cast<size_t>(info.size) <= size) {
                    convert(buf + info.offset, static_cast<size_t>(info.size), info.presentationTimeUs);
                }
                AMediaCodec_releaseOutputBuffer(m_codec, static_cast<size_t>(out), false);
                if (m_pcmFrames > 0) return true;
                progress = true;
            } else if (out == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                readOutputFormat();
                progress = true;
            } else if (out == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
                progress = true;
            }
            if (progress) idle = 0;
            else if (++idle > kMaxIdleSteps) return false;
        }
        return false;
    }

    void readOutputFormat() {
        AMediaFormat* format = AMediaCodec_getOutputFormat(m_codec);
        if (!format) return;
        int32_t value = 0;
        if (AMediaFormat_getInt32(format, "sample-rate", &value) && value > 0) m_outRate = static_cast<uint32_t>(value);
        if (AMediaFormat_getInt32(format, "channel-count", &value) && value > 0) m_outChannels = static_cast<size_t>(value);
        m_encoding = AMediaFormat_getInt32(format, "pcm-encoding", &value) ? value : kEncoding16;
        AMediaFormat_delete(format);
        if (!m_opened && m_outRate > 0 && m_outChannels > 0) {
            // The decoder knows better than the container: HE-AAC (SBR) doubles
            // the rate, parametric stereo turns mono into stereo.
            if (m_outRate != m_rate) {
                const double scale = static_cast<double>(m_outRate) / m_rate;
                m_total = static_cast<uint64_t>(llround(m_total * scale));
                m_delay = llround(m_delay * scale);
                m_rate = m_outRate;
            }
            m_channels = m_outChannels;
        }
    }

    /** Appends a decoded block to m_pcm, dropping encoder delay and anything before a seek target. */
    void convert(const uint8_t* data, size_t bytes, int64_t ptsUs) {
        if (m_outChannels != m_channels || m_outRate != m_rate) return;   // changed mid-stream: not playable
        const size_t sampleBytes = m_encoding == kEncodingFloat || m_encoding == kEncoding32 ? 4
                                 : m_encoding == kEncoding24Packed ? 3 : 2;
        const size_t frames = bytes / (sampleBytes * m_channels);
        // Frame index in the trimmed timeline of the first frame in this block.
        const int64_t first = llround(static_cast<double>(ptsUs) * m_rate / 1e6) - m_delay;
        const int64_t skip = std::clamp<int64_t>(m_discardBefore - first, 0, static_cast<int64_t>(frames));
        const size_t keep = frames - static_cast<size_t>(skip);
        if (keep == 0) return;
        m_discardBefore = first + static_cast<int64_t>(frames);   // later blocks are never older
        m_pcm.resize(keep * m_channels);
        const uint8_t* p = data + static_cast<size_t>(skip) * sampleBytes * m_channels;
        for (size_t i = 0; i < keep * m_channels; ++i, p += sampleBytes) {
            switch (m_encoding) {
                case kEncodingFloat: {
                    float f;
                    std::memcpy(&f, p, sizeof(f));
                    m_pcm[i] = f;
                    break;
                }
                case kEncoding32: {
                    int32_t v;
                    std::memcpy(&v, p, sizeof(v));
                    m_pcm[i] = v / 2147483648.0;
                    break;
                }
                case kEncoding24Packed: {
                    int32_t v = p[0] | (p[1] << 8) | (p[2] << 16);
                    if (v & 0x800000) v -= 0x1000000;
                    m_pcm[i] = v / 8388608.0;
                    break;
                }
                default: {
                    int16_t v;
                    std::memcpy(&v, p, sizeof(v));
                    m_pcm[i] = v / 32768.0;
                    break;
                }
            }
        }
        m_pcmFrames = keep;
        m_pcmPos = 0;
    }

    bool fail() {
        closeDecoder();
        return false;
    }

    void closeDecoder() {
        if (m_codec) {
            if (m_started) AMediaCodec_stop(m_codec);
            AMediaCodec_delete(m_codec);
        }
        if (m_extractor) AMediaExtractor_delete(m_extractor);
        if (m_fd >= 0) ::close(m_fd);
        m_codec = nullptr;
        m_extractor = nullptr;
        m_fd = -1;
        m_started = false;
        m_opened = false;
        m_pcmFrames = m_pcmPos = 0;
    }

    int m_fd = -1;
    AMediaExtractor* m_extractor = nullptr;
    AMediaCodec* m_codec = nullptr;
    bool m_started = false;
    bool m_opened = false;        // first block decoded; the format is fixed from here on
    std::string m_mime;
    uint32_t m_rate = 0;
    size_t m_channels = 0;
    uint32_t m_outRate = 0;
    size_t m_outChannels = 0;
    int32_t m_encoding = kEncoding16;
    uint32_t m_bits = 0;
    int64_t m_delay = 0;
    uint64_t m_total = 0;
    uint64_t m_position = 0;
    int64_t m_discardBefore = 0;
    bool m_inputDone = false;
    bool m_outputDone = false;
    std::vector<double> m_pcm;     // interleaved, current output block
    size_t m_pcmFrames = 0;
    size_t m_pcmPos = 0;
};

} // namespace decoders
} // namespace audio_engine
