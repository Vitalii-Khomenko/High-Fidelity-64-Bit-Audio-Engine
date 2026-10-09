#pragma once

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "TextCodec.h"

namespace audio_engine {
namespace tags {

struct ReplayGainInfo {
    float trackGainDb = 0.0f;
    float trackPeak = 0.0f;     // linear, 0 = unknown
    float albumGainDb = 0.0f;
    float albumPeak = 0.0f;
    bool hasTrack = false;
    bool hasAlbum = false;
};

struct Picture {
    std::string mime;
    int type = -1;                  // ID3 / FLAC picture type: 3 = front cover
    std::vector<uint8_t> data;
};

struct Tags {
    std::string title, artist, album, albumArtist, genre, year, lyrics;
    int track = 0, trackTotal = 0, disc = 0, discTotal = 0;
    ReplayGainInfo replayGain;
    bool hasPicture = false;
    Picture picture;                // only filled when asked for
};

// ── Small helpers ───────────────────────────────────────────────────────────

inline uint32_t be16(const uint8_t* p) { return (uint32_t(p[0]) << 8) | p[1]; }
inline uint32_t be24(const uint8_t* p) { return (uint32_t(p[0]) << 16) | (uint32_t(p[1]) << 8) | p[2]; }
inline uint32_t be32(const uint8_t* p) { return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3]; }
inline uint64_t be64(const uint8_t* p) { return (uint64_t(be32(p)) << 32) | be32(p + 4); }
inline uint32_t le32(const uint8_t* p) { return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24); }
inline uint64_t le64(const uint8_t* p) { return uint64_t(le32(p)) | (uint64_t(le32(p + 4)) << 32); }
inline uint32_t synchsafe32(const uint8_t* p) {
    return (uint32_t(p[0] & 0x7f) << 21) | (uint32_t(p[1] & 0x7f) << 14) | (uint32_t(p[2] & 0x7f) << 7) | uint32_t(p[3] & 0x7f);
}

inline std::string upper(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
    return s;
}

inline std::string trim(const std::string& s) {
    size_t a = 0, b = s.size();
    while (a < b && (s[a] == ' ' || s[a] == '\t' || s[a] == '\r' || s[a] == '\n' || s[a] == '\0')) ++a;
    while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\t' || s[b - 1] == '\r' || s[b - 1] == '\n' || s[b - 1] == '\0')) --b;
    return s.substr(a, b - a);
}

/** Locale-independent parse of values like "-6.48 dB", "+1.2", "0,98". */
inline bool parseNumber(const std::string& text, float& out) {
    size_t i = 0;
    while (i < text.size() && (text[i] == ' ' || text[i] == '\t')) ++i;
    bool negative = false;
    if (i < text.size() && (text[i] == '+' || text[i] == '-')) negative = text[i++] == '-';
    double value = 0.0;
    bool digits = false;
    while (i < text.size() && std::isdigit(static_cast<unsigned char>(text[i]))) {
        value = value * 10.0 + (text[i++] - '0');
        digits = true;
    }
    if (i < text.size() && (text[i] == '.' || text[i] == ',')) {
        ++i;
        double scale = 0.1;
        while (i < text.size() && std::isdigit(static_cast<unsigned char>(text[i]))) {
            value += (text[i++] - '0') * scale;
            scale *= 0.1;
            digits = true;
        }
    }
    if (!digits) return false;
    out = static_cast<float>(negative ? -value : value);
    return std::isfinite(out);
}

/** "3", "03/12" → number and optional total. */
inline void parseIndex(const std::string& text, int& number, int& total) {
    int a = 0, b = 0;
    size_t i = 0;
    while (i < text.size() && text[i] == ' ') ++i;
    bool any = false;
    while (i < text.size() && std::isdigit(static_cast<unsigned char>(text[i])) && a < 100000) { a = a * 10 + (text[i++] - '0'); any = true; }
    if (!any) return;
    if (number == 0) number = a;
    if (i < text.size() && text[i] == '/') {
        ++i;
        while (i < text.size() && std::isdigit(static_cast<unsigned char>(text[i])) && b < 100000) b = b * 10 + (text[i++] - '0');
        if (total == 0 && b > 0) total = b;
    }
}

inline int parseInt(const std::string& text) {
    int n = 0, t = 0;
    parseIndex(text, n, t);
    return n;
}

inline std::vector<uint8_t> base64Decode(const std::string& in) {
    static const auto value = [](char c) -> int {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+' || c == '-') return 62;
        if (c == '/' || c == '_') return 63;
        return -1;
    };
    std::vector<uint8_t> out;
    out.reserve(in.size() * 3 / 4);
    uint32_t acc = 0;
    int bits = 0;
    for (char c : in) {
        const int v = value(c);
        if (v < 0) continue;   // padding, whitespace
        acc = (acc << 6) | static_cast<uint32_t>(v);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(static_cast<uint8_t>(acc >> bits));
        }
    }
    return out;
}

inline const char* id3Genre(int index) {
    static const char* const names[] = {
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
        "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion", "Bebop", "Latin", "Revival", "Celtic", "Bluegrass",
        "Avantgarde", "Gothic Rock", "Progressive Rock", "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band", "Chorus", "Easy Listening", "Acoustic",
        "Humour", "Speech", "Chanson", "Opera", "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus", "Porn Groove",
        "Satire", "Slow Jam", "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle",
        "Duet", "Punk Rock", "Drum Solo", "A Cappella", "Euro-House", "Dance Hall", "Goa", "Drum & Bass", "Club-House", "Hardcore",
        "Terror", "Indie", "BritPop", "Negerpunk", "Polsk Punk", "Beat", "Christian Gangsta Rap", "Heavy Metal", "Black Metal", "Crossover",
        "Contemporary Christian", "Christian Rock", "Merengue", "Salsa", "Thrash Metal", "Anime", "JPop", "Synthpop",
    };
    const int count = static_cast<int>(sizeof(names) / sizeof(names[0]));
    return index >= 0 && index < count ? names[index] : "";
}

/** "(17)", "17", "(17)Rock", "Rock" → readable genre. */
inline std::string id3GenreText(const std::string& raw) {
    std::string s = trim(raw);
    if (s.size() >= 3 && s[0] == '(' && std::isdigit(static_cast<unsigned char>(s[1]))) {
        const size_t close = s.find(')');
        if (close != std::string::npos) {
            const std::string rest = trim(s.substr(close + 1));
            if (!rest.empty()) return rest;
            return id3Genre(std::atoi(s.c_str() + 1));
        }
    }
    if (!s.empty() && std::all_of(s.begin(), s.end(), [](unsigned char c) { return std::isdigit(c); })) {
        return id3Genre(std::atoi(s.c_str()));
    }
    return s;
}

// ── Reader ──────────────────────────────────────────────────────────────────

/**
 * Reads title / artist / album / numbering / genre / year / lyrics,
 * ReplayGain and the cover picture from FLAC, Ogg (Vorbis, Opus, FLAC), MP3,
 * WAV, AIFF, DSF, DFF, MP4 / M4A, WavPack, APE and TTA files.
 *
 * Several tag blocks can exist in one file (e.g. ID3v2 + APEv2 + ID3v1); the
 * first one read fills a field, later ones only fill what is still empty.
 * Never moves the descriptor's file offset (pread only).
 */
class TagReader {
public:
    static constexpr size_t kMaxBlock = 16u << 20;     // a metadata block with embedded art
    static constexpr size_t kMaxText = 1u << 20;       // one text frame / lyrics

    TagReader(int fd, bool wantPicture) : m_fd(fd), m_wantPicture(wantPicture) {
        struct stat st {};
        m_size = fd >= 0 && ::fstat(fd, &st) == 0 && st.st_size > 0 ? static_cast<uint64_t>(st.st_size) : 0;
    }

    Tags read() {
        uint8_t h[12] = {};
        if (m_fd < 0 || !at(0, h, sizeof(h))) return m_tags;
        uint64_t start = 0;
        if (!std::memcmp(h, "ID3", 3)) {
            start = id3v2(0);
            if (!at(start, h, sizeof(h))) start = 0, std::memset(h, 0, sizeof(h));
        }
        bool tailTags = false;
        if (!std::memcmp(h, "fLaC", 4)) flac(start + 4);
        else if (!std::memcmp(h, "OggS", 4)) ogg(start);
        else if (!std::memcmp(h, "RIFF", 4) || !std::memcmp(h, "RF64", 4)) riff(start);
        else if (!std::memcmp(h, "FORM", 4)) aiff(start);
        else if (!std::memcmp(h, "FRM8", 4)) dff(start);
        else if (!std::memcmp(h, "DSD ", 4)) dsf(start);
        else if (!std::memcmp(h + 4, "ftyp", 4)) mp4();
        else tailTags = true;   // MP3, APE, WavPack, TTA and unknown: tags live at the end
        if (tailTags) {
            apev2();
            id3v1();
        }
        finish();
        return m_tags;
    }

private:
    // ── I/O ─────────────────────────────────────────────────────────────────

    bool at(uint64_t offset, void* dst, size_t bytes) const {
        if (m_size > 0 && (offset > m_size || bytes > m_size - offset)) return false;
        auto* out = static_cast<uint8_t*>(dst);
        while (bytes > 0) {
            const ssize_t n = ::pread(m_fd, out, bytes, static_cast<off_t>(offset));
            if (n <= 0) return false;
            out += n;
            offset += static_cast<uint64_t>(n);
            bytes -= static_cast<size_t>(n);
        }
        return true;
    }

    std::vector<uint8_t> block(uint64_t offset, uint64_t bytes, size_t limit = kMaxBlock) const {
        if (bytes == 0 || bytes > limit) return {};
        std::vector<uint8_t> out(static_cast<size_t>(bytes));
        if (!at(offset, out.data(), out.size())) return {};
        return out;
    }

    // ── Fields ──────────────────────────────────────────────────────────────

    static void set(std::string& field, const std::string& value) {
        if (field.empty()) field = trim(value);
    }

    void replayGain(const std::string& rawKey, const std::string& value) {
        const std::string key = upper(trim(rawKey));
        float number = 0.0f;
        if (!parseNumber(value, number)) return;
        ReplayGainInfo& rg = m_tags.replayGain;
        if (key == "REPLAYGAIN_TRACK_GAIN") { if (!rg.hasTrack) { rg.trackGainDb = number; rg.hasTrack = true; } }
        else if (key == "REPLAYGAIN_TRACK_PEAK") { if (rg.trackPeak == 0.0f) rg.trackPeak = number; }
        else if (key == "REPLAYGAIN_ALBUM_GAIN") { if (!rg.hasAlbum) { rg.albumGainDb = number; rg.hasAlbum = true; } }
        else if (key == "REPLAYGAIN_ALBUM_PEAK") { if (rg.albumPeak == 0.0f) rg.albumPeak = number; }
    }

    /** "KEY=value" style fields (Vorbis comments, APEv2, MP4 freeform). */
    void field(const std::string& rawKey, const std::string& value) {
        const std::string key = upper(trim(rawKey));
        if (key.rfind("REPLAYGAIN_", 0) == 0) { replayGain(key, value); return; }
        if (key == "TITLE") set(m_tags.title, value);
        else if (key == "ARTIST") appendMulti(m_artists, value);
        else if (key == "ALBUM") set(m_tags.album, value);
        else if (key == "ALBUMARTIST" || key == "ALBUM ARTIST" || key == "ALBUM_ARTIST") set(m_tags.albumArtist, value);
        else if (key == "GENRE") appendMulti(m_genres, value);
        else if (key == "DATE" || key == "YEAR") set(m_tags.year, value);
        else if (key == "TRACKNUMBER" || key == "TRACK") parseIndex(value, m_tags.track, m_tags.trackTotal);
        else if (key == "TRACKTOTAL" || key == "TOTALTRACKS") { if (!m_tags.trackTotal) m_tags.trackTotal = parseInt(value); }
        else if (key == "DISCNUMBER" || key == "DISC") parseIndex(value, m_tags.disc, m_tags.discTotal);
        else if (key == "DISCTOTAL" || key == "TOTALDISCS") { if (!m_tags.discTotal) m_tags.discTotal = parseInt(value); }
        else if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "UNSYNCED LYRICS") set(m_tags.lyrics, value);
        else if (key == "METADATA_BLOCK_PICTURE") {
            if (!m_wantPicture) { offerPicture(-1, "", nullptr, 0, true); return; }
            const std::vector<uint8_t> raw = base64Decode(value);
            flacPicture(raw.data(), raw.size());
        } else if (key == "COVERART") {
            if (wantsPicture(3)) offerPicture(3, "", base64Decode(value));
            else offerPicture(3, "", nullptr, 0, true);
        }
    }

    // Opus stores loudness relative to its header gain at -23 LUFS (Q7.8 dB);
    // ReplayGain targets about -18 LUFS, i.e. 5 dB more.
    void r128(const std::string& key, const std::string& value) {
        float q = 0.0f;
        if (!parseNumber(value, q)) return;
        const float db = q / 256.0f + 5.0f;
        ReplayGainInfo& rg = m_tags.replayGain;
        if (key == "R128_TRACK_GAIN" && !rg.hasTrack) { rg.trackGainDb = db; rg.hasTrack = true; }
        if (key == "R128_ALBUM_GAIN" && !rg.hasAlbum) { rg.albumGainDb = db; rg.hasAlbum = true; }
    }

    static void appendMulti(std::vector<std::string>& list, const std::string& value) {
        const std::string v = trim(value);
        if (!v.empty() && std::find(list.begin(), list.end(), v) == list.end()) list.push_back(v);
    }

    /** Keeps the best picture: a front cover beats anything else, otherwise the first one. */
    void offerPicture(int type, const std::string& mime, const uint8_t* data, size_t size, bool presenceOnly = false) {
        m_tags.hasPicture = true;
        if (presenceOnly || !m_wantPicture || !data || size == 0) return;
        if (!m_tags.picture.data.empty() && (m_pictureType == 3 || type != 3)) return;
        m_tags.picture.data.assign(data, data + size);
        m_tags.picture.mime = mime;
        m_tags.picture.type = type;
        m_pictureType = type;
    }
    void offerPicture(int type, const std::string& mime, const std::vector<uint8_t>& data) {
        offerPicture(type, mime, data.data(), data.size());
    }
    bool wantsPicture(int type) const {
        return m_wantPicture && (m_tags.picture.data.empty() || (type == 3 && m_pictureType != 3));
    }

    void finish() {
        if (m_tags.artist.empty() && !m_artists.empty()) m_tags.artist = join(m_artists);
        if (m_tags.genre.empty() && !m_genres.empty()) m_tags.genre = join(m_genres);
        // "2001-05-12" or "2001" → "2001".
        const std::string& y = m_tags.year;
        if (y.size() > 4 && std::all_of(y.begin(), y.begin() + 4, [](unsigned char c) { return std::isdigit(c); })) {
            m_tags.year = y.substr(0, 4);
        }
        if (m_tags.lyrics.size() > kMaxText) m_tags.lyrics.resize(kMaxText);
    }

    static std::string join(const std::vector<std::string>& list) {
        std::string out;
        for (const auto& s : list) {
            if (!out.empty()) out += "; ";
            out += s;
        }
        return out;
    }

    // ── Vorbis comments and FLAC ────────────────────────────────────────────

    void vorbisComments(const uint8_t* p, size_t n, bool opus) {
        if (n < 8) return;
        size_t pos = 4 + static_cast<size_t>(le32(p));
        if (pos + 4 > n || pos < 4) return;
        const uint32_t count = le32(p + pos);
        pos += 4;
        for (uint32_t i = 0; i < count && pos + 4 <= n; ++i) {
            const uint32_t len = le32(p + pos);
            pos += 4;
            if (len > n - pos) break;
            const std::string comment(reinterpret_cast<const char*>(p + pos), len);
            pos += len;
            const size_t eq = comment.find('=');
            if (eq == std::string::npos) continue;
            const std::string key = upper(comment.substr(0, eq));
            const std::string value = comment.substr(eq + 1);
            if (opus && (key == "R128_TRACK_GAIN" || key == "R128_ALBUM_GAIN")) r128(key, value);
            else field(key, value);
        }
    }

    /** FLAC PICTURE block body (also the payload of METADATA_BLOCK_PICTURE). */
    void flacPicture(const uint8_t* p, size_t n) {
        if (n < 32) return;
        const int type = static_cast<int>(be32(p));
        size_t pos = 4;
        const uint32_t mimeLen = be32(p + pos); pos += 4;
        if (mimeLen > n - pos) return;
        const std::string mime(reinterpret_cast<const char*>(p + pos), mimeLen); pos += mimeLen;
        if (pos + 4 > n) return;
        const uint32_t descLen = be32(p + pos); pos += 4;
        if (descLen > n - pos || n - pos - descLen < 20) return;
        pos += descLen + 16;
        const uint32_t dataLen = be32(p + pos); pos += 4;
        if (dataLen > n - pos) return;
        offerPicture(type, mime, p + pos, dataLen);
    }

    void flac(uint64_t pos) {
        for (int guard = 0; guard < 256; ++guard) {
            uint8_t h[4];
            if (!at(pos, h, 4)) return;
            const bool last = (h[0] & 0x80) != 0;
            const int type = h[0] & 0x7f;
            const uint32_t length = be24(h + 1);
            pos += 4;
            if (type == 4) {
                const auto data = block(pos, length);
                vorbisComments(data.data(), data.size(), false);
            } else if (type == 6) {
                // Peek at the type first so a big back cover is not read for nothing.
                uint8_t t[4];
                const int pictureType = at(pos, t, 4) ? static_cast<int>(be32(t)) : -1;
                if (wantsPicture(pictureType)) {
                    const auto data = block(pos, length);
                    flacPicture(data.data(), data.size());
                } else {
                    offerPicture(pictureType, "", nullptr, 0, true);
                }
            }
            pos += length;
            if (last) return;
        }
    }

    // ── Ogg ─────────────────────────────────────────────────────────────────

    /** Reassembles the second packet (the comment header) of the first logical stream. */
    void ogg(uint64_t pos) {
        std::vector<uint8_t> packet;
        int index = 0;
        uint32_t serial = 0;
        bool haveSerial = false;
        for (int pages = 0; pages < 4096; ++pages) {
            uint8_t h[27];
            if (!at(pos, h, sizeof(h)) || std::memcmp(h, "OggS", 4) != 0) return;
            const uint32_t pageSerial = le32(h + 14);
            const size_t segments = h[26];
            uint8_t lacing[255];
            if (!at(pos + 27, lacing, segments)) return;
            uint64_t body = pos + 27 + segments;
            uint64_t bodySize = 0;
            for (size_t i = 0; i < segments; ++i) bodySize += lacing[i];
            if (!haveSerial) { serial = pageSerial; haveSerial = true; }
            if (pageSerial == serial) {
                uint64_t offset = body;
                for (size_t i = 0; i < segments; ++i) {
                    if (index == 1 && lacing[i] > 0) {
                        if (packet.size() + lacing[i] > kMaxBlock) return;
                        const size_t old = packet.size();
                        packet.resize(old + lacing[i]);
                        if (!at(offset, packet.data() + old, lacing[i])) return;
                    }
                    offset += lacing[i];
                    if (lacing[i] < 255) {   // end of a packet
                        if (index == 1) { oggComment(packet); return; }
                        ++index;
                    }
                }
            }
            pos = body + bodySize;
        }
    }

    void oggComment(const std::vector<uint8_t>& p) {
        if (p.size() >= 7 && p[0] == 0x03 && !std::memcmp(p.data() + 1, "vorbis", 6)) {
            vorbisComments(p.data() + 7, p.size() - 7, false);
        } else if (p.size() >= 8 && !std::memcmp(p.data(), "OpusTags", 8)) {
            vorbisComments(p.data() + 8, p.size() - 8, true);
        } else if (p.size() >= 4 && (p[0] & 0x7f) == 4) {   // Ogg FLAC: a VORBIS_COMMENT metadata block
            vorbisComments(p.data() + 4, p.size() - 4, false);
        }
    }

    // ── ID3v2 ───────────────────────────────────────────────────────────────

    static size_t terminatorSize(uint8_t encoding) { return encoding == 1 || encoding == 2 ? 2 : 1; }

    /** Index of the terminator of the string starting at pos (n when unterminated). */
    static size_t stringEnd(const uint8_t* p, size_t n, size_t pos, uint8_t encoding) {
        const size_t step = terminatorSize(encoding);
        while (pos + step <= n) {
            if (p[pos] == 0 && (step == 1 || p[pos + 1] == 0)) return pos;
            pos += step;
        }
        return n;
    }

    /** Offset just past the encoded string that starts at pos. */
    static size_t skipString(const uint8_t* p, size_t n, size_t pos, uint8_t encoding) {
        const size_t end = stringEnd(p, n, pos, encoding);
        return std::min(n, end + terminatorSize(encoding));
    }

    static std::string decodeText(const uint8_t* p, size_t n, uint8_t encoding) {
        switch (encoding) {
            case 1: {
                bool big = false;
                if (n >= 2 && p[0] == 0xFE && p[1] == 0xFF) big = true;
                return fromUtf16(p, n, big);
            }
            case 2: return fromUtf16(p, n, true);
            case 3: return fromUtf8OrLegacy(p, n);
            default: return fromLatin1(p, n);
        }
    }

    /** All strings of a text frame (ID3v2.4 separates multiple values with NUL). */
    static std::vector<std::string> textValues(const uint8_t* p, size_t n) {
        std::vector<std::string> values;
        if (n < 1) return values;
        const uint8_t encoding = p[0];
        size_t pos = 1;
        while (pos < n) {
            const size_t end = stringEnd(p, n, pos, encoding);
            const std::string v = trim(decodeText(p + pos, end - pos, encoding));
            if (!v.empty()) values.push_back(v);
            pos = end + terminatorSize(encoding);
        }
        return values;
    }

    void id3Frame(const char* id, const uint8_t* p, size_t n) {
        const std::string frame(id);
        auto first = [&]() {
            const auto values = textValues(p, n);
            return values.empty() ? std::string() : values.front();
        };
        if (frame == "TIT2" || frame == "TT2") set(m_tags.title, first());
        else if (frame == "TPE1" || frame == "TP1") { for (const auto& v : textValues(p, n)) appendMulti(m_artists, v); }
        else if (frame == "TALB" || frame == "TAL") set(m_tags.album, first());
        else if (frame == "TPE2" || frame == "TP2") set(m_tags.albumArtist, first());
        else if (frame == "TCON" || frame == "TCO") { for (const auto& v : textValues(p, n)) appendMulti(m_genres, id3GenreText(v)); }
        else if (frame == "TYER" || frame == "TYE" || frame == "TDRC") set(m_tags.year, first());
        else if (frame == "TRCK" || frame == "TRK") parseIndex(first(), m_tags.track, m_tags.trackTotal);
        else if (frame == "TPOS" || frame == "TPA") parseIndex(first(), m_tags.disc, m_tags.discTotal);
        else if ((frame == "TXXX" || frame == "TXX") && n > 1) {
            const uint8_t encoding = p[0];
            const size_t keyEnd = stringEnd(p, n, 1, encoding);
            const size_t valueAt = skipString(p, n, 1, encoding);
            const std::string key = decodeText(p + 1, keyEnd - 1, encoding);
            const std::string value = valueAt < n ? decodeText(p + valueAt, n - valueAt, encoding) : std::string();
            const std::string k = upper(trim(key));
            if (k == "ALBUM ARTIST" || k == "ALBUMARTIST") set(m_tags.albumArtist, value);
            else replayGain(k, value);
        } else if ((frame == "USLT" || frame == "ULT") && n > 4) {
            const uint8_t encoding = p[0];
            const size_t textAt = skipString(p, n, 4, encoding);
            if (textAt < n) set(m_tags.lyrics, decodeText(p + textAt, n - textAt, encoding));
        } else if (frame == "APIC" && n > 4) {
            const uint8_t encoding = p[0];
            const size_t mimeEnd = skipString(p, n, 1, 0);
            const std::string mime = fromLatin1(p + 1, mimeEnd - 1);
            if (mimeEnd >= n) return;
            const int type = p[mimeEnd];
            const size_t dataAt = skipString(p, n, mimeEnd + 1, encoding);
            if (dataAt < n) offerPicture(type, mime, p + dataAt, n - dataAt);
        } else if (frame == "PIC" && n > 6) {
            const uint8_t encoding = p[0];
            const std::string format(reinterpret_cast<const char*>(p + 1), 3);
            const int type = p[4];
            const size_t dataAt = skipString(p, n, 5, encoding);
            const std::string mime = upper(format) == "PNG" ? "image/png" : "image/jpeg";
            if (dataAt < n) offerPicture(type, mime, p + dataAt, n - dataAt);
        }
    }

    static bool wantedId3(const std::string& id) {
        static const char* const ids[] = {"TIT2", "TPE1", "TALB", "TPE2", "TCON", "TYER", "TDRC", "TRCK", "TPOS",
                                          "TXXX", "USLT", "APIC", "TT2", "TP1", "TAL", "TP2", "TCO", "TYE", "TRK",
                                          "TPA", "TXX", "ULT", "PIC"};
        for (const char* w : ids) if (id == w) return true;
        return false;
    }

    static std::vector<uint8_t> unsynchronise(const uint8_t* p, size_t n) {
        std::vector<uint8_t> out;
        out.reserve(n);
        for (size_t i = 0; i < n; ++i) {
            out.push_back(p[i]);
            if (p[i] == 0xFF && i + 1 < n && p[i + 1] == 0x00) ++i;
        }
        return out;
    }

    /** Parses an ID3v2 tag at offset; returns the offset just past it (offset if none). */
    uint64_t id3v2(uint64_t offset) {
        uint8_t h[10];
        if (!at(offset, h, 10) || std::memcmp(h, "ID3", 3) != 0) return offset;
        const int version = h[3];
        const uint8_t flags = h[5];
        const uint32_t size = synchsafe32(h + 6);
        const uint64_t end = offset + 10 + size + ((flags & 0x10) ? 10 : 0);
        if (version < 2 || version > 4 || size == 0) return end;

        // Whole-tag unsynchronisation (v2.2 / v2.3): read and clean the whole tag.
        // Otherwise frames are read one by one, so a big picture is not read unless wanted.
        std::vector<uint8_t> memory;
        const bool inMemory = (flags & 0x80) && version < 4;
        if (inMemory) {
            const auto raw = block(offset + 10, size, kMaxBlock * 4);
            if (raw.empty()) return end;
            memory = unsynchronise(raw.data(), raw.size());
        }
        const uint64_t tagSize = inMemory ? memory.size() : size;
        auto read = [&](uint64_t pos, uint8_t* dst, size_t bytes) {
            if (inMemory) {
                if (pos > memory.size() || bytes > memory.size() - pos) return false;
                std::memcpy(dst, memory.data() + pos, bytes);
                return true;
            }
            return at(offset + 10 + pos, dst, bytes);
        };

        uint64_t pos = 0;
        if ((flags & 0x40) && version >= 3) {   // extended header
            uint8_t e[4];
            if (!read(0, e, 4)) return end;
            pos = version == 4 ? synchsafe32(e) : be32(e) + 4;
        }
        const size_t headerLen = version == 2 ? 6 : 10;
        while (pos + headerLen <= tagSize) {
            uint8_t fh[10] = {};
            if (!read(pos, fh, headerLen) || fh[0] == 0) break;   // padding
            char id[5] = {};
            std::memcpy(id, fh, version == 2 ? 3 : 4);
            uint32_t frameSize = version == 2 ? be24(fh + 3) : version == 3 ? be32(fh + 4) : synchsafe32(fh + 4);
            const uint8_t frameFlags = version == 2 ? 0 : fh[9];
            pos += headerLen;
            if (frameSize > tagSize - pos) break;
            const uint64_t payloadPos = pos;
            pos += frameSize;
            if (!wantedId3(id)) continue;
            const bool picture = !std::strcmp(id, "APIC") || !std::strcmp(id, "PIC");
            if (picture && !m_wantPicture) {
                offerPicture(-1, "", nullptr, 0, true);
                continue;
            }
            // v2.3: compressed / encrypted frames are skipped; grouping adds a byte.
            // v2.4: same, plus per-frame unsynchronisation and a data length indicator.
            uint64_t skip = 0;
            bool frameUnsync = false;
            if (version == 3) {
                if (frameFlags & 0xC0) continue;
                if (frameFlags & 0x20) skip = 1;
            } else if (version == 4) {
                if (frameFlags & 0x0C) continue;
                if (frameFlags & 0x40) skip += 1;
                if (frameFlags & 0x01) skip += 4;
                frameUnsync = (frameFlags & 0x02) != 0;
            }
            if (skip >= frameSize) continue;
            const size_t limit = picture ? kMaxBlock : kMaxText;
            const size_t bytes = static_cast<size_t>(frameSize - skip);
            if (bytes > limit) continue;
            std::vector<uint8_t> payload(bytes);
            if (!read(payloadPos + skip, payload.data(), bytes)) break;
            if (frameUnsync) payload = unsynchronise(payload.data(), payload.size());
            id3Frame(id, payload.data(), payload.size());
        }
        return end;
    }

    // ── ID3v1, APEv2 (end of file) ──────────────────────────────────────────

    void id3v1() {
        if (m_size < 128) return;
        uint8_t t[128];
        if (!at(m_size - 128, t, sizeof(t)) || std::memcmp(t, "TAG", 3) != 0) return;
        auto text = [&](size_t offset, size_t len) {
            size_t n = 0;
            while (n < len && t[offset + n] != 0) ++n;
            return trim(fromLatin1(t + offset, n));
        };
        set(m_tags.title, text(3, 30));
        if (m_artists.empty()) appendMulti(m_artists, text(33, 30));
        set(m_tags.album, text(63, 30));
        set(m_tags.year, text(93, 4));
        if (t[125] == 0 && t[126] != 0 && m_tags.track == 0) m_tags.track = t[126];
        if (m_genres.empty() && t[127] != 0xFF) appendMulti(m_genres, id3Genre(t[127]));
    }

    void apev2() {
        if (m_size < 32) return;
        for (uint64_t footerAt : {m_size - 32, m_size >= 160 ? m_size - 160 : uint64_t(0)}) {
            uint8_t f[32];
            if (!at(footerAt, f, sizeof(f)) || std::memcmp(f, "APETAGEX", 8) != 0) continue;
            const uint32_t size = le32(f + 12);
            const uint32_t count = le32(f + 16);
            if (size < 32 || size > footerAt + 32) return;
            const uint64_t start = footerAt + 32 - size;
            const uint64_t end = footerAt;
            uint64_t pos = start;
            for (uint32_t i = 0; i < count && pos + 9 <= end; ++i) {
                uint8_t ih[8];
                if (!at(pos, ih, 8)) return;
                const uint32_t valueSize = le32(ih);
                const uint32_t itemFlags = le32(ih + 4);
                pos += 8;
                char keyBuf[256] = {};
                const size_t keyRead = static_cast<size_t>(std::min<uint64_t>(sizeof(keyBuf), end - pos));
                if (!at(pos, keyBuf, keyRead)) return;
                const size_t keyLen = strnlen(keyBuf, keyRead);
                if (keyLen == keyRead) return;   // no terminator: damaged tag
                const std::string key(keyBuf, keyLen);
                pos += keyLen + 1;
                if (valueSize > end - std::min(end, pos)) return;
                const int kind = (itemFlags >> 1) & 3;
                const std::string k = upper(key);
                if (kind == 1 && k.rfind("COVER ART", 0) == 0) {
                    const int type = k == "COVER ART (FRONT)" ? 3 : k == "COVER ART (BACK)" ? 4 : 0;
                    if (wantsPicture(type)) {
                        const auto data = block(pos, valueSize);
                        // "<file name>\0<image data>"
                        const auto nul = std::find(data.begin(), data.end(), uint8_t(0));
                        if (nul != data.end()) {
                            const size_t skip = static_cast<size_t>(nul - data.begin()) + 1;
                            offerPicture(type, "", data.data() + skip, data.size() - skip);
                        }
                    } else {
                        offerPicture(type, "", nullptr, 0, true);
                    }
                } else if (kind == 0 && valueSize <= kMaxText) {
                    const auto data = block(pos, valueSize, kMaxText);
                    // Multiple values are NUL-separated.
                    std::string value(data.begin(), data.end());
                    std::replace(value.begin(), value.end(), '\0', ';');
                    if (k == "ARTIST") {
                        size_t s = 0;
                        while (s <= value.size()) {
                            const size_t e = value.find(';', s);
                            appendMulti(m_artists, value.substr(s, e == std::string::npos ? std::string::npos : e - s));
                            if (e == std::string::npos) break;
                            s = e + 1;
                        }
                    } else {
                        field(k, value);
                    }
                }
                pos += valueSize;
            }
            return;
        }
    }

    // ── Chunked containers ──────────────────────────────────────────────────

    void riff(uint64_t base) {
        uint64_t pos = base + 12;
        uint64_t ds64Data = 0;
        for (int guard = 0; guard < 1024 && pos + 8 <= m_size; ++guard) {
            uint8_t ch[8];
            if (!at(pos, ch, 8)) return;
            uint64_t size = le32(ch + 4);
            if (!std::memcmp(ch, "ds64", 4)) {
                uint8_t d[16];
                if (at(pos + 8, d, 16)) ds64Data = le64(d + 8);
            }
            if (!std::memcmp(ch, "data", 4) && size == 0xFFFFFFFFu && ds64Data) size = ds64Data;
            if (!std::memcmp(ch, "id3 ", 4) || !std::memcmp(ch, "ID3 ", 4)) id3v2(pos + 8);
            else if (!std::memcmp(ch, "LIST", 4)) riffInfo(pos + 8, size);
            pos += 8 + size + (size & 1u);
        }
    }

    void riffInfo(uint64_t pos, uint64_t size) {
        uint8_t type[4];
        if (size < 4 || !at(pos, type, 4) || std::memcmp(type, "INFO", 4) != 0) return;
        const uint64_t end = pos + size;
        pos += 4;
        while (pos + 8 <= end) {
            uint8_t ch[8];
            if (!at(pos, ch, 8)) return;
            const uint32_t len = le32(ch + 4);
            if (len > end - pos - 8) return;
            const auto data = block(pos + 8, len, kMaxText);
            const std::string value = fromUtf8OrLegacy(data.data(), data.size());
            if (!std::memcmp(ch, "INAM", 4)) set(m_tags.title, value);
            else if (!std::memcmp(ch, "IART", 4)) appendMulti(m_artists, value);
            else if (!std::memcmp(ch, "IPRD", 4)) set(m_tags.album, value);
            else if (!std::memcmp(ch, "IGNR", 4)) appendMulti(m_genres, value);
            else if (!std::memcmp(ch, "ICRD", 4)) set(m_tags.year, value);
            else if (!std::memcmp(ch, "ITRK", 4) || !std::memcmp(ch, "IPRT", 4)) parseIndex(value, m_tags.track, m_tags.trackTotal);
            pos += 8 + len + (len & 1u);
        }
    }

    void aiff(uint64_t base) {
        uint64_t pos = base + 12;
        for (int guard = 0; guard < 1024 && pos + 8 <= m_size; ++guard) {
            uint8_t ch[8];
            if (!at(pos, ch, 8)) return;
            const uint32_t size = be32(ch + 4);
            if (!std::memcmp(ch, "ID3 ", 4) || !std::memcmp(ch, "id3 ", 4)) id3v2(pos + 8);
            else if (!std::memcmp(ch, "NAME", 4) || !std::memcmp(ch, "AUTH", 4)) {
                const auto data = block(pos + 8, size, kMaxText);
                const std::string value = fromUtf8OrLegacy(data.data(), data.size());
                if (ch[0] == 'N') set(m_tags.title, value); else appendMulti(m_artists, value);
            }
            pos += 8 + size + (size & 1u);
        }
    }

    void dff(uint64_t base) {
        uint64_t pos = base + 16;   // "FRM8", size (8), "DSD "
        for (int guard = 0; guard < 1024 && pos + 12 <= m_size; ++guard) {
            uint8_t ch[12];
            if (!at(pos, ch, 12)) return;
            const uint64_t size = be64(ch + 4);
            if (!std::memcmp(ch, "ID3 ", 4)) id3v2(pos + 12);
            if (size > m_size) return;
            pos += 12 + size + (size & 1u);
        }
    }

    void dsf(uint64_t base) {
        uint8_t d[28];
        if (!at(base, d, sizeof(d))) return;
        const uint64_t metadata = le64(d + 20);
        if (metadata > 0 && metadata < m_size) id3v2(metadata);
    }

    // ── MP4 ─────────────────────────────────────────────────────────────────

    struct Atom {
        uint64_t start = 0;      // payload start
        uint64_t end = 0;        // payload end
        char type[5] = {};
    };

    bool atom(uint64_t pos, uint64_t limit, Atom& a) const {
        uint8_t h[16];
        if (pos + 8 > limit || !at(pos, h, 8)) return false;
        uint64_t size = be32(h);
        uint64_t header = 8;
        if (size == 1) {
            if (pos + 16 > limit || !at(pos + 8, h + 8, 8)) return false;
            size = be64(h + 8);
            header = 16;
        } else if (size == 0) {
            size = limit - pos;
        }
        if (size < header || size > limit - pos) return false;
        std::memcpy(a.type, h + 4, 4);
        a.start = pos + header;
        a.end = pos + size;
        return true;
    }

    bool child(uint64_t from, uint64_t to, const char* type, Atom& out) const {
        uint64_t pos = from;
        Atom a;
        for (int guard = 0; guard < 4096 && atom(pos, to, a); ++guard) {
            if (!std::memcmp(a.type, type, 4)) { out = a; return true; }
            pos = a.end;
        }
        return false;
    }

    void mp4() {
        Atom moov, udta, meta, ilst;
        if (!child(0, m_size, "moov", moov)) return;
        bool found = child(moov.start, moov.end, "udta", udta) && child(udta.start, udta.end, "meta", meta);
        if (!found) found = child(moov.start, moov.end, "meta", meta);
        if (!found) return;
        // 'meta' is a full box (4 bytes version/flags), except in some old QuickTime files.
        uint8_t probe[8];
        uint64_t metaStart = meta.start + 4;
        if (at(meta.start, probe, 8) && !std::memcmp(probe + 4, "hdlr", 4)) metaStart = meta.start;
        if (!child(metaStart, meta.end, "ilst", ilst)) return;

        uint64_t pos = ilst.start;
        Atom item;
        for (int guard = 0; guard < 4096 && atom(pos, ilst.end, item); ++guard) {
            pos = item.end;
            mp4Item(item);
        }
    }

    void mp4Item(const Atom& item) {
        const auto* t = reinterpret_cast<const uint8_t*>(item.type);
        const bool copyright = t[0] == 0xA9;
        const std::string name(item.type + (copyright ? 1 : 0), copyright ? 3 : 4);
        if (!std::memcmp(item.type, "----", 4)) {
            Atom n, d;
            if (!child(item.start, item.end, "name", n) || !child(item.start, item.end, "data", d)) return;
            const auto key = block(n.start + 4, n.end - n.start - 4, 1024);
            const auto value = block(d.start + 8, d.end - d.start - 8, kMaxText);
            field(std::string(key.begin(), key.end()), std::string(value.begin(), value.end()));
            return;
        }
        Atom d;
        if (!child(item.start, item.end, "data", d) || d.end - d.start < 8) return;
        const uint64_t payload = d.start + 8;
        const uint64_t length = d.end - payload;
        if (name == "covr") {
            uint8_t typeBytes[4];
            const uint32_t dataType = at(d.start, typeBytes, 4) ? be32(typeBytes) & 0xFFFFFF : 0;
            const std::string mime = dataType == 14 ? "image/png" : "image/jpeg";
            if (wantsPicture(3)) offerPicture(3, mime, block(payload, length));
            else offerPicture(3, mime, nullptr, 0, true);
            return;
        }
        if (name == "trkn" || name == "disk") {
            uint8_t v[6];
            if (length < 6 || !at(payload, v, 6)) return;
            int& number = name == "trkn" ? m_tags.track : m_tags.disc;
            int& total = name == "trkn" ? m_tags.trackTotal : m_tags.discTotal;
            if (!number) number = static_cast<int>(be16(v + 2));
            if (!total) total = static_cast<int>(be16(v + 4));
            return;
        }
        if (name == "gnre") {
            uint8_t v[2];
            if (length >= 2 && at(payload, v, 2) && m_genres.empty()) appendMulti(m_genres, id3Genre(static_cast<int>(be16(v)) - 1));
            return;
        }
        if (!copyright && name != "aART") return;
        const auto data = block(payload, length, kMaxText);
        const std::string value = fromUtf8OrLegacy(data.data(), data.size());
        if (name == "nam") set(m_tags.title, value);
        else if (name == "ART") appendMulti(m_artists, value);
        else if (name == "alb") set(m_tags.album, value);
        else if (name == "aART") set(m_tags.albumArtist, value);
        else if (name == "gen") appendMulti(m_genres, value);
        else if (name == "day") set(m_tags.year, value);
        else if (name == "lyr") set(m_tags.lyrics, value);
    }

    int m_fd;
    bool m_wantPicture;
    uint64_t m_size = 0;
    Tags m_tags;
    int m_pictureType = -1;
    std::vector<std::string> m_artists;
    std::vector<std::string> m_genres;
};

inline Tags readTags(int fd, bool wantPicture = false) { return TagReader(fd, wantPicture).read(); }

} // namespace tags
} // namespace audio_engine
