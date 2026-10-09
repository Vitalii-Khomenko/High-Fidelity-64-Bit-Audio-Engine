#pragma once
// Host-side stand-in for the NDK media API, used to exercise
// MediaCodecDecoder's buffer handling in the native tests. The "codec" does
// not compress anything: see NdkMediaExtractor.h for the fake container.
// Android builds always use the real libmediandk.
#include <cstdint>
#include <cstring>
#include <map>
#include <string>
#include <vector>

typedef int32_t media_status_t;
enum : int32_t { AMEDIA_OK = 0, AMEDIA_ERROR_UNKNOWN = -10000 };

struct AMediaFormat {
    std::map<std::string, int32_t> i32;
    std::map<std::string, int64_t> i64;
    std::map<std::string, std::string> str;
    std::map<std::string, std::vector<uint8_t>> buf;
};

inline AMediaFormat* AMediaFormat_new() { return new AMediaFormat(); }
inline media_status_t AMediaFormat_delete(AMediaFormat* f) { delete f; return AMEDIA_OK; }
inline bool AMediaFormat_getInt32(AMediaFormat* f, const char* name, int32_t* out) {
    auto it = f->i32.find(name);
    if (it == f->i32.end()) return false;
    *out = it->second;
    return true;
}
inline bool AMediaFormat_getInt64(AMediaFormat* f, const char* name, int64_t* out) {
    auto it = f->i64.find(name);
    if (it == f->i64.end()) return false;
    *out = it->second;
    return true;
}
inline bool AMediaFormat_getString(AMediaFormat* f, const char* name, const char** out) {
    auto it = f->str.find(name);
    if (it == f->str.end()) return false;
    *out = it->second.c_str();
    return true;
}
inline bool AMediaFormat_getBuffer(AMediaFormat* f, const char* name, void** data, size_t* size) {
    auto it = f->buf.find(name);
    if (it == f->buf.end()) return false;
    *data = it->second.data();
    *size = it->second.size();
    return true;
}
inline void AMediaFormat_setInt32(AMediaFormat* f, const char* name, int32_t value) { f->i32[name] = value; }
