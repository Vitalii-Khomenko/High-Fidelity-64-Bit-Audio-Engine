#pragma once

#include "IAudioDecoder.h"

#include <algorithm>
#include <cmath>
#include <memory>

namespace audio_engine {
namespace decoders {

/**
 * A section [start, end) of another decoder, used for CUE sheet tracks: frame
 * 0 of the range is the track's INDEX 01. Consecutive ranges of one file are
 * sample-adjacent, so a CUE album plays gaplessly through the normal
 * next-track mechanism.
 */
class RangeDecoder : public IAudioDecoder {
public:
    /** Times in microseconds; endUs <= 0 means "to the end of the file". */
    static std::unique_ptr<IAudioDecoder> wrap(std::unique_ptr<IAudioDecoder> inner, int64_t startUs, int64_t endUs) {
        if (!inner || (startUs <= 0 && endUs <= 0)) return inner;
        const double rate = inner->getSampleRate();
        const uint64_t start = startUs > 0 ? static_cast<uint64_t>(std::llround(startUs * rate / 1e6)) : 0;
        uint64_t end = endUs > 0 ? static_cast<uint64_t>(std::llround(endUs * rate / 1e6)) : 0;
        const uint64_t total = inner->getTotalFrames();
        if (total > 0 && (end == 0 || end > total)) end = total;
        if ((end > 0 && end <= start) || (total > 0 && start >= total)) return nullptr;
        auto range = std::unique_ptr<RangeDecoder>(new RangeDecoder(std::move(inner), start, end));
        if (!range->seekToFrame(0)) return nullptr;
        return range;
    }

    bool openFd(int) override { return false; }   // created through wrap()

    size_t readFrames(core::AudioBuffer& buffer, size_t framesToRead) override {
        if (m_end > 0) {
            const uint64_t at = m_start + m_position;
            framesToRead = static_cast<size_t>(std::min<uint64_t>(framesToRead, at < m_end ? m_end - at : 0));
        }
        if (framesToRead == 0) return 0;
        const size_t n = m_inner->readFrames(buffer, framesToRead);
        m_position += n;
        return n;
    }

    bool seekToFrame(uint64_t frame) override {
        const uint64_t length = getTotalFrames();
        if (length > 0) frame = std::min(frame, length);
        if (!m_inner->seekToFrame(m_start + frame)) return false;
        m_position = frame;
        return true;
    }

    uint32_t getSampleRate() const override { return m_inner->getSampleRate(); }
    size_t getNumChannels() const override { return m_inner->getNumChannels(); }
    uint32_t getBitsPerSample() const override { return m_inner->getBitsPerSample(); }
    uint64_t getTotalFrames() const override { return m_end > m_start ? m_end - m_start : 0; }
    uint64_t getCurrentFrame() const override { return m_position; }
    Codec getCodec() const override { return m_inner->getCodec(); }
    uint32_t getDsdRate() const override { return m_inner->getDsdRate(); }
    FileSource* fileSource() override { return m_inner->fileSource(); }

private:
    RangeDecoder(std::unique_ptr<IAudioDecoder> inner, uint64_t start, uint64_t end)
        : m_inner(std::move(inner)), m_start(start), m_end(end) {}

    std::unique_ptr<IAudioDecoder> m_inner;
    uint64_t m_start;
    uint64_t m_end;        // 0 = unknown length, play to the end
    uint64_t m_position = 0;
};

} // namespace decoders
} // namespace audio_engine
