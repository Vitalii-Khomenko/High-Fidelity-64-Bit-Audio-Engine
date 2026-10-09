#pragma once

#include <vector>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <mutex>
#include <limits>
#include <thread>

namespace audio_engine {
namespace core {

template <typename T>
class RingBuffer {
public:
    explicit RingBuffer(size_t capacity) {
        if (capacity == 0) {
            throw std::invalid_argument("Capacity must be greater than zero."); 
        }

        m_capacity = 1;
        while (m_capacity <= capacity) {
            if (m_capacity > std::numeric_limits<size_t>::max() / 2)
                throw std::length_error("Ring buffer capacity overflow");
            m_capacity <<= 1;
        }
        m_mask = m_capacity - 1;

        m_buffer.resize(m_capacity);
        m_readIndex.store(0, std::memory_order_relaxed);
        m_writeIndex.store(0, std::memory_order_relaxed);
    }

    ~RingBuffer() = default;

    RingBuffer(const RingBuffer&) = delete;
    RingBuffer& operator=(const RingBuffer&) = delete;

    size_t write(const T* data, size_t numElements) {
        if (!data || numElements == 0) return 0;
        OperationGuard operation(m_writerActive, m_resetting);
        if (!operation.entered) return 0;
        size_t currentWrite = m_writeIndex.load(std::memory_order_relaxed);     
        size_t nextRead = m_readIndex.load(std::memory_order_acquire);
        size_t available = (nextRead - currentWrite - 1) & m_mask;
        size_t toWrite = (numElements < available) ? numElements : available;   
        if (toWrite == 0) return 0;
        size_t nextWriteBase = currentWrite;
        for (size_t i = 0; i < toWrite; ++i) {
            m_buffer[nextWriteBase] = data[i];
            nextWriteBase = (nextWriteBase + 1) & m_mask;
        }
        m_writeIndex.store(nextWriteBase, std::memory_order_release);
        return toWrite;
    }

    size_t read(T* data, size_t maxElements) {
        if (!data || maxElements == 0) return 0;
        // SPSC hot path: no mutex and no waiting in the audio callback.
        OperationGuard operation(m_readerActive, m_resetting);
        if (!operation.entered) return 0;
        size_t currentRead = m_readIndex.load(std::memory_order_relaxed);       
        size_t nextWrite = m_writeIndex.load(std::memory_order_acquire);        
        size_t available = (nextWrite - currentRead) & m_mask;
        size_t toRead = (maxElements < available) ? maxElements : available;    
        if (toRead == 0) return 0;
        size_t nextReadBase = currentRead;
        for (size_t i = 0; i < toRead; ++i) {
            data[i] = m_buffer[nextReadBase];
            nextReadBase = (nextReadBase + 1) & m_mask;
        }
        m_readIndex.store(nextReadBase, std::memory_order_release);
        m_consumedSamples.fetch_add(toRead, std::memory_order_release);
        return toRead;
    }

    uint64_t getConsumedSamples() const {
        return m_consumedSamples.load(std::memory_order_acquire);
    }

    size_t getAvailableRead() const {
        size_t readIdx = m_readIndex.load(std::memory_order_acquire);
        size_t writeIdx = m_writeIndex.load(std::memory_order_acquire);
        return (writeIdx - readIdx) & m_mask;
    }

    size_t getAvailableWrite() const {
        size_t readIdx = m_readIndex.load(std::memory_order_acquire);
        size_t writeIdx = m_writeIndex.load(std::memory_order_acquire);
        return (readIdx - writeIdx - 1) & m_mask;
    }

    void clear() {
        // Only flush callers serialize. In-flight copies finish before indices
        // reset; new read/write calls return immediately during this window.
        std::lock_guard<std::mutex> lock(m_flushMutex);
        m_resetting.store(true);
        while (m_writerActive.load() || m_readerActive.load()) std::this_thread::yield();
        m_writeIndex.store(0, std::memory_order_release);
        m_readIndex.store(0, std::memory_order_release);
        m_resetting.store(false);
    }

private:
    struct OperationGuard {
        std::atomic<bool>& active;
        bool entered;
        OperationGuard(std::atomic<bool>& flag, const std::atomic<bool>& resetting)
            : active(flag) {
            // Sequential consistency pairs entry with the control thread's
            // reset gate: either clear observes this operation, or it aborts.
            active.store(true);
            entered = !resetting.load();
        }
        ~OperationGuard() { active.store(false); }
    };
    std::mutex m_flushMutex;
    alignas(64) std::atomic<bool> m_resetting{false};
    alignas(64) std::atomic<bool> m_writerActive{false};
    alignas(64) std::atomic<bool> m_readerActive{false};
    std::vector<T> m_buffer;
    size_t m_capacity;
    size_t m_mask;

    alignas(64) std::atomic<uint64_t> m_consumedSamples{0};
    alignas(64) std::atomic<size_t> m_readIndex;
    alignas(64) std::atomic<size_t> m_writeIndex;
};

} // namespace core
} // namespace audio_engine
