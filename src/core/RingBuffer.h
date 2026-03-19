#pragma once

#include <vector>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <stdexcept>

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
        return toRead;
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
        m_writeIndex.store(0, std::memory_order_release);
        m_readIndex.store(0, std::memory_order_release);
    }

private:
    std::vector<T> m_buffer;
    size_t m_capacity;
    size_t m_mask;

    alignas(64) std::atomic<size_t> m_readIndex;
    alignas(64) std::atomic<size_t> m_writeIndex;
};

} // namespace core
} // namespace audio_engine
