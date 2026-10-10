#pragma once

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <vector>

#if defined(__linux__)
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>
#endif

namespace audio_engine {
namespace usb {

enum class UsbSpeed : int { Unknown = 0, Low = 1, Full = 2, High = 3, Super = 5 };

/** One isochronous transfer: a contiguous buffer split into packets. */
struct IsoTransfer {
    uint8_t endpoint = 0;
    std::vector<uint8_t> buffer;
    std::vector<uint32_t> length;   // requested bytes per packet
    std::vector<uint32_t> actual;   // filled on completion
    int status = 0;                 // 0, or -errno for the whole transfer
    void* impl = nullptr;           // the transport's own record
    size_t packets() const { return length.size(); }
};

/**
 * The small part of USB the driver needs. Implemented over usbfs ioctls on
 * Android/Linux (UsbfsTransport) and by a simulated DAC in the tests.
 * Every call except wake() comes from the thread that owns the device.
 */
class UsbTransport {
public:
    virtual ~UsbTransport() = default;
    virtual UsbSpeed speed() const = 0;
    /** Takes the interface from the kernel's audio driver. */
    virtual bool claimInterface(int iface) = 0;
    /** Releases it and hands it back to the kernel driver (Android's own USB audio). */
    virtual void releaseInterface(int iface) = 0;
    virtual bool setInterface(int iface, int alt) = 0;
    /** Returns bytes transferred or -errno. */
    virtual int control(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index,
                        uint8_t* data, uint16_t length, unsigned timeoutMs) = 0;

    /** Allocates a transfer of `packets` packets with room for `bytes`. Owned by the transport. */
    virtual IsoTransfer* allocIso(uint8_t endpoint, size_t packets, size_t bytes) = 0;
    virtual void freeIso(IsoTransfer* t) = 0;
    /** 0 or -errno (-ENODEV once the device is gone). */
    virtual int submit(IsoTransfer* t) = 0;
    /** Asks the transport to cancel a submitted transfer; it still completes (status -ENOENT). */
    virtual void discard(IsoTransfer* t) = 0;
    /**
     * Waits up to timeoutMs for a completed transfer. nullptr on timeout with
     * *error = 0, or on failure with *error = -errno.
     */
    virtual IsoTransfer* reap(int timeoutMs, int* error) = 0;
};

#if defined(__linux__)
/**
 * usbfs on a file descriptor from UsbDeviceConnection.getFileDescriptor().
 * Does not own the descriptor: the app closes the connection afterwards.
 */
class UsbfsTransport : public UsbTransport {
public:
    explicit UsbfsTransport(int fd) : m_fd(fd) {}
    ~UsbfsTransport() override {
        for (auto* t : m_all) freeRecord(t);
    }

    UsbSpeed speed() const override {
#ifdef USBDEVFS_GET_SPEED
        const int s = ioctl(m_fd, USBDEVFS_GET_SPEED, nullptr);
        if (s > 0) return static_cast<UsbSpeed>(s >= 5 ? 5 : s);
#endif
        return UsbSpeed::Unknown;
    }

    bool claimInterface(int iface) override {
        usbdevfs_ioctl command{};
        command.ifno = iface;
        command.ioctl_code = USBDEVFS_DISCONNECT;
        ioctl(m_fd, USBDEVFS_IOCTL, &command);   // fails harmlessly when no driver is bound
        unsigned int n = static_cast<unsigned int>(iface);
        return ioctl(m_fd, USBDEVFS_CLAIMINTERFACE, &n) == 0;
    }

    void releaseInterface(int iface) override {
        unsigned int n = static_cast<unsigned int>(iface);
        ioctl(m_fd, USBDEVFS_RELEASEINTERFACE, &n);
        usbdevfs_ioctl command{};
        command.ifno = iface;
        command.ioctl_code = USBDEVFS_CONNECT;
        ioctl(m_fd, USBDEVFS_IOCTL, &command);
    }

    bool setInterface(int iface, int alt) override {
        usbdevfs_setinterface s{};
        s.interface = static_cast<unsigned int>(iface);
        s.altsetting = static_cast<unsigned int>(alt);
        return ioctl(m_fd, USBDEVFS_SETINTERFACE, &s) == 0;
    }

    int control(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index,
                uint8_t* data, uint16_t length, unsigned timeoutMs) override {
        usbdevfs_ctrltransfer c{};
        c.bRequestType = requestType;
        c.bRequest = request;
        c.wValue = value;
        c.wIndex = index;
        c.wLength = length;
        c.timeout = timeoutMs;
        c.data = data;
        const int r = ioctl(m_fd, USBDEVFS_CONTROL, &c);
        return r < 0 ? -errno : r;
    }

    IsoTransfer* allocIso(uint8_t endpoint, size_t packets, size_t bytes) override {
        auto* t = new IsoTransfer();
        t->endpoint = endpoint;
        t->buffer.assign(bytes, 0);
        t->length.assign(packets, 0);
        t->actual.assign(packets, 0);
        const size_t size = sizeof(usbdevfs_urb) + packets * sizeof(usbdevfs_iso_packet_desc);
        auto* urb = static_cast<usbdevfs_urb*>(std::calloc(1, size));
        urb->usercontext = t;
        t->impl = urb;
        m_all.push_back(t);
        return t;
    }

    void freeIso(IsoTransfer* t) override {
        for (auto it = m_all.begin(); it != m_all.end(); ++it) {
            if (*it == t) { m_all.erase(it); break; }
        }
        freeRecord(t);
    }

    int submit(IsoTransfer* t) override {
        auto* urb = static_cast<usbdevfs_urb*>(t->impl);
        const size_t n = t->packets();
        std::memset(urb, 0, sizeof(usbdevfs_urb));
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = t->endpoint;
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer = t->buffer.data();
        urb->number_of_packets = static_cast<int>(n);
        urb->usercontext = t;
        int total = 0;
        for (size_t i = 0; i < n; ++i) {
            urb->iso_frame_desc[i].length = t->length[i];
            urb->iso_frame_desc[i].actual_length = 0;
            urb->iso_frame_desc[i].status = 0;
            total += static_cast<int>(t->length[i]);
        }
        urb->buffer_length = total;
        return ioctl(m_fd, USBDEVFS_SUBMITURB, urb) == 0 ? 0 : -errno;
    }

    void discard(IsoTransfer* t) override { ioctl(m_fd, USBDEVFS_DISCARDURB, t->impl); }

    IsoTransfer* reap(int timeoutMs, int* error) override {
        *error = 0;
        for (int attempt = 0; attempt < 2; ++attempt) {
            usbdevfs_urb* urb = nullptr;
            if (ioctl(m_fd, USBDEVFS_REAPURBNDELAY, &urb) == 0 && urb) {
                auto* t = static_cast<IsoTransfer*>(urb->usercontext);
                t->status = urb->status;
                for (size_t i = 0; i < t->packets(); ++i) t->actual[i] = urb->iso_frame_desc[i].actual_length;
                return t;
            }
            if (errno != EAGAIN) {
                *error = -errno;
                return nullptr;
            }
            if (attempt == 1) break;
            // usbfs signals a completed URB as writable.
            pollfd p{m_fd, POLLOUT, 0};
            const int r = poll(&p, 1, timeoutMs);
            if (r < 0 && errno != EINTR) { *error = -errno; return nullptr; }
            if (r > 0 && (p.revents & (POLLERR | POLLHUP))) { *error = -ENODEV; return nullptr; }
            if (r == 0) return nullptr;
        }
        return nullptr;
    }

private:
    static void freeRecord(IsoTransfer* t) {
        std::free(t->impl);
        delete t;
    }

    int m_fd;
    std::vector<IsoTransfer*> m_all;
};
#endif

}  // namespace usb
}  // namespace audio_engine
