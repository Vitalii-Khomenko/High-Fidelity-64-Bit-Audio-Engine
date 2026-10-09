package com.aiproject.musicplayer

/** Tags as read by the native reader (src/tags/TagReader.h). Empty strings / 0 when absent. */
data class AudioTags(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val year: String = "",
    val lyrics: String = "",
    val track: Int = 0,
    val trackTotal: Int = 0,
    val disc: Int = 0,
    val discTotal: Int = 0,
    val hasPicture: Boolean = false,
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
) {
    companion object {
        /** Parses the NUL-separated UTF-8 record built by NativeTags.readTags (JNI). */
        fun parse(record: ByteArray): AudioTags {
            val f = String(record, Charsets.UTF_8).split('\u0000')
            fun at(i: Int) = f.getOrNull(i).orEmpty()
            return AudioTags(
                title = at(0), artist = at(1), album = at(2), albumArtist = at(3), genre = at(4), year = at(5),
                lyrics = at(6), track = at(7).toIntOrNull() ?: 0, trackTotal = at(8).toIntOrNull() ?: 0,
                disc = at(9).toIntOrNull() ?: 0, discTotal = at(10).toIntOrNull() ?: 0, hasPicture = at(11) == "1",
                trackGainDb = at(12).toFloatOrNull(), albumGainDb = at(13).toFloatOrNull(),
            )
        }
    }
}

/**
 * Stateless native helpers. The caller keeps ownership of the descriptor;
 * nothing moves its file offset.
 */
object NativeTags {
    init {
        System.loadLibrary("audioengine")
    }

    fun tags(fd: Int): AudioTags? = readTags(fd)?.let(AudioTags::parse)

    /** Embedded cover (front cover preferred), or null. */
    fun picture(fd: Int): ByteArray? = readPicture(fd)

    /** Duration from the engine's own decoders (formats MediaMetadataRetriever does not know), or 0. */
    fun durationMs(fd: Int): Long = probeDurationMs(fd)

    @JvmStatic private external fun readTags(fd: Int): ByteArray?
    @JvmStatic private external fun readPicture(fd: Int): ByteArray?
    @JvmStatic private external fun probeDurationMs(fd: Int): Long
}
