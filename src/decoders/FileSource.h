#pragma once

#include <cstdint>
#include <sys/stat.h>
#include <unistd.h>

namespace audio_engine {
namespace decoders {

/**
 * Seekable byte source over a private dup() of a file descriptor.
 *
 * It keeps its own cursor and reads with pread(), so decoders never disturb
 * (or get disturbed by) other users of the same open file description.
 */
class FileSource {
public:
    FileSource() = default;
    ~FileSource() { close(); }
    FileSource(const FileSource&) = delete;
    FileSource& operator=(const FileSource&) = delete;

    bool open(int fd) {
        close();
        if (fd < 0) return false;
        m_fd = ::dup(fd);
        if (m_fd < 0) return false;
        struct stat st {};
        m_size = (::fstat(m_fd, &st) == 0 && st.st_size > 0) ? static_cast<uint64_t>(st.st_size) : 0;
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
    int m_fd = -1;
    uint64_t m_offset = 0;
    uint64_t m_size = 0;
};

} // namespace decoders
} // namespace audio_engine
