#pragma once

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>

namespace audio_engine {
namespace decoders {

/**
 * A file that is still being downloaded (DLNA streaming). The app reports
 * progress; readers wait for bytes that are not there yet.
 */
struct StreamState {
    enum Status : int { Downloading = 0, Complete = 1, Failed = 2 };
    std::atomic<uint64_t> available{0};
    std::atomic<uint64_t> total{0};       // final size (Content-Length)
    std::atomic<int> status{Downloading};
};

/**
 * Seekable byte source over a private dup() of a file descriptor.
 *
 * It keeps its own cursor and reads with pread(), so decoders never disturb
 * (or get disturbed by) other users of the same open file description.
 *
 * Attached to a StreamState, the size is the final size and a read past the
 * downloaded part waits for it. The wait ends early when the download fails,
 * stalls for kStallSeconds, or the abort flag (set by the player while it
 * stops its decode thread) is raised; a read cut short that way is recorded
 * so the player can re-sync the decoder with a seek.
 */
class FileSource {
public:
    static constexpr int kStallSeconds = 20;

    FileSource() = default;
    ~FileSource() { close(); }
    FileSource(const FileSource&) = delete;
    FileSource& operator=(const FileSource&) = delete;

    /** Before open(): read through a download in progress. */
    void attachStream(std::shared_ptr<StreamState> stream) { m_stream = std::move(stream); }
    void setAbortFlag(const std::atomic<bool>* flag) { m_abort = flag; }
    /** True once after a read was cut short by the abort flag. */
    bool consumeInterrupted() { return m_interrupted.exchange(false); }

    bool open(int fd) {
        close();
        if (fd < 0) return false;
        m_fd = ::dup(fd);
        if (m_fd < 0) return false;
        struct stat st {};
        m_size = (::fstat(m_fd, &st) == 0 && st.st_size > 0) ? static_cast<uint64_t>(st.st_size) : 0;
        if (m_stream && m_stream->total.load() > 0) m_size = m_stream->total.load();
        m_offset = 0;
        return true;
    }

    void close() {
        if (m_fd >= 0) ::close(m_fd);
        m_fd = -1;
        m_offset = 0;
        m_size = 0;
    }

    bool isOpen() const { return m_fd >= 0; }
    uint64_t size() const { return m_size; }
    uint64_t tell() const { return m_offset; }

    size_t read(void* dst, size_t bytes) {
        if (m_stream && !waitFor(m_offset + bytes)) {
            // Only what is there (a failed or stalled download ends the stream here).
            const uint64_t have = m_stream->available.load();
            bytes = have > m_offset ? static_cast<size_t>(std::min<uint64_t>(bytes, have - m_offset)) : 0;
        }
        auto* out = static_cast<uint8_t*>(dst);
        size_t total = 0;
        while (total < bytes) {
            const ssize_t n = ::pread(m_fd, out + total, bytes - total, static_cast<off_t>(m_offset));
            if (n <= 0) break;
            total += static_cast<size_t>(n);
            m_offset += static_cast<uint64_t>(n);
        }
        return total;
    }

    /** origin: 0 = set, 1 = current, 2 = end (dr_libs ordering). */
    bool seek(int64_t offset, int origin) {
        int64_t base = 0;
        if (origin == 1) base = static_cast<int64_t>(m_offset);
        else if (origin == 2) base = static_cast<int64_t>(m_size);
        const int64_t target = base + offset;
        if (target < 0) return false;
        m_offset = static_cast<uint64_t>(target);
        return true;
    }

    // dr_libs compatible callbacks (pUserData is the FileSource).
    static size_t onRead(void* user, void* dst, size_t bytes) {
        return static_cast<FileSource*>(user)->read(dst, bytes);
    }
    static int onSeekImpl(void* user, int offset, int origin) {
        return static_cast<FileSource*>(user)->seek(offset, origin) ? 1 : 0;
    }
    static int onTellImpl(void* user, int64_t* cursor) {
        *cursor = static_cast<int64_t>(static_cast<FileSource*>(user)->tell());
        return 1;
    }

private:
    /** Waits until [0, end) is downloaded. False when it never will be (or not now). */
    bool waitFor(uint64_t end) {
        end = std::min(end, m_size);
        uint64_t last = m_stream->available.load();
        auto progressAt = std::chrono::steady_clock::now();
        for (;;) {
            const uint64_t have = m_stream->available.load(std::memory_order_acquire);
            if (have >= end || m_stream->status.load() == StreamState::Complete) return true;
            if (m_stream->status.load() == StreamState::Failed) return false;
            if (m_abort && m_abort->load(std::memory_order_acquire)) {
                m_interrupted.store(true);
                return false;
            }
            const auto now = std::chrono::steady_clock::now();
            if (have != last) {
                last = have;
                progressAt = now;
            } else if (now - progressAt > std::chrono::seconds(kStallSeconds)) {
                return false;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
    }

    int m_fd = -1;
    uint64_t m_offset = 0;
    uint64_t m_size = 0;
    std::shared_ptr<StreamState> m_stream;
    const std::atomic<bool>* m_abort = nullptr;
    std::atomic<bool> m_interrupted{false};
};

} // namespace decoders
} // namespace audio_engine
