#pragma once
#include <unistd.h>
#include <fcntl.h>
#include <stddef.h>

class FdReader {
public:
    static size_t onRead(void* pUserData, void* pBufferOut, size_t bytesToRead) {
        int fd = *static_cast<int*>(pUserData);
        ssize_t bytesRead = read(fd, pBufferOut, bytesToRead);
        return bytesRead > 0 ? static_cast<size_t>(bytesRead) : 0;
    }

    static int onSeek(void* pUserData, int offset, int origin) {
        int fd = *static_cast<int*>(pUserData);
        int whence = SEEK_SET; // 0
        if (origin == 1) whence = SEEK_CUR; 
        if (origin == 2) whence = SEEK_END; 
        
        off_t newPos = lseek(fd, offset, whence);
        return newPos >= 0 ? 1 /* true */ : 0 /* false */;
    }

    // Since dr_libs uses custom drflac_int64 typedefs per header, we use an untyped interface and cast
    // Wait, the signature in C++ for the function pointer must match.
};
