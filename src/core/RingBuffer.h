#pragma once

#include <vector>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <stdexcept>

namespace audio_engine {
namespace core {

/**
 * @brief Lock-free Single-Producer, Single-Consumer (SPSC) RingBuffer.
 * 
 * Optimized for safely passing audio frames between decoding threads and the 
 * hard, real-time audio output thread without dynamic allocation or locks.
 * 
 * @tparam T The element type, e.g., double
 */
template <typename T>
class RingBuffer {
public:
    /**
     * @brief Construct a lock-free RingBuffer with a specific capacity.
     * @param capacity The size of the buffer. Will be bounded to the next power of 2
     *                 internally for optimal bitwise masking (faster than modulo).
     */
    explicit RingBuffer(size_t capacity) {
        if (capacity == 0) {
            throw std::invalid_argument("Capacity must be greater than zero.");
        }
        
        // Find next power of 2 for strict bitwise wrapping
        m_capacity = 1;
        while (m_capacity <= capacity) {
            m_capacity <<= 1;
        }
        m_mask = m_capacity - 1;

        m_buffer.resize(m_capacity);
        m_readIndex.store(0, std::memory_order_relaxed);
        m_writeIndex.store(0, std::memory_order_relaxed);
    }

    ~RingBuffer() = default;

    // Delete copy options to prevent accidental usage in real-time threads
    RingBuffer(const RingBuffer&) = delete;
    RingBuffer& operator=(const RingBuffer&) = delete;

    /**
     * @brief Try to push audio data elements continuously.
     * @param data The elements to push.
     * @param numElements The number of elements to push.
     * @return The number of elements successfully written.
     */
    size_t write(const T* data, size_t numElements) {
        if (!data || numElements == 0) return 0;

        size_t currentWrite = m_writeIndex.load(std::memory_order_relaxed);
        size_t nextRead = m_readIndex.load(std::memory_order_acquire);
        
        // Calculate available slots via bitwise mask
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

    /**
     * @brief Try to pull audio data elements out of the queue continuously.
     * @param data The buffer to write the popped elements to.
     * @param maxElements The maximum number of elements to pop.
     * @return The number of elements actually read.
     */
    size_t read(T* data, size_t maxElements) {
        if (!data || maxElements == 0) return 0;

        size_t currentRead = m_readIndex.load(std::memory_order_relaxed);
        size_t nextWrite = m_writeIndex.load(std::memory_order_acquire);

        // Mask to handle wrapping gracefully
        size_t available = (nextWrite - currentRead) & m_mask;
        size_t toRead = (maxElements < available) ? maxElements : available;

        if (toRead == 0) return 0;

        size_t nextReadBase = currentRead;
        for (size_t i = 0; i < toRead; ++i) {
            data[i] = m_buffer[nextReadBase];
            nextReadBase = (nextReadBase + 1) & m_mask;
        }

        m_readIndex.store(nextReadBase, std::memory_order_release);
        return toRead;
    }

    /**
     * @brief Gets current amount of readable elements.
     */
    size_t getAvailableRead() const {
        size_t readIdx = m_readIndex.load(std::memory_order_acquire);
        size_t writeIdx = m_writeIndex.load(std::memory_order_acquire);
        return (writeIdx - readIdx) & m_mask;
    }

    /**
     * @brief Gets current amount of available writable slots in buffer.
     */
    size_t getAvailableWrite() const {
        size_t readIdx = m_readIndex.load(std::memory_order_acquire);
        size_t writeIdx = m_writeIndex.load(std::memory_order_acquire);
        return (readIdx - writeIdx - 1) & m_mask;
    }

private:
    std::vector<T> m_buffer;
    size_t m_capacity;
    size_t m_mask;
    
    // Aligned to cache lines to prevent false sharing
    alignas(64) std::atomic<size_t> m_readIndex;
    alignas(64) std::atomic<size_t> m_writeIndex;
};

} // namespace core
} // namespace audio_engine
