package com.aiproject.musicplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.aiproject.musicplayer.NativeTags
import com.aiproject.musicplayer.db.MusicDatabase
import com.aiproject.musicplayer.playback.PlayableUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Cover pictures: the embedded picture of a file, else the folder picture
 * (cover.jpg, folder.jpg, …). Pictures are scaled to at most [MAX_PX],
 * stored once per picture (tracks of an album share one file) under
 * cacheDir/covers, and kept in a small memory cache per requested size.
 *
 *   covers/ref/<sha1(file uri)>   → picture key, or "-" for none
 *   covers/img/<picture key>.jpg
 */
object CoverArt {
    const val MAX_PX = 600
    private const val MAX_SOURCE_BYTES = 24L shl 20
    private const val MAX_CACHE_BYTES = 150L shl 20
    private const val NONE = "-"
    private const val RETRY_NONE_MS = 7L * 24 * 3600 * 1000

    private val memory = object : LruCache<String, Bitmap>(24 shl 20) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun fileKey(trackUri: String): String = sha1(PlayableUri.split(trackUri).fileUri)

    private fun root(context: Context) = File(context.cacheDir, "covers")
    private fun refFile(context: Context, fileKey: String) = File(root(context), "ref/$fileKey")
    private fun imageFile(context: Context, imageKey: String) = File(root(context), "img/$imageKey.jpg")

    /** Whether the track can have a cover we can read (local files only; DLNA has none here). */
    fun isLocal(trackUri: String): Boolean {
        val file = PlayableUri.split(trackUri).fileUri
        return file.startsWith("content:") || file.startsWith("file:")
    }

    /** Cover scaled to at most [px] pixels, or null. */
    suspend fun load(context: Context, trackUri: String, px: Int): Bitmap? {
        if (!isLocal(trackUri)) return null
        val key = fileKey(trackUri)
        val memoryKey = "$key@$px"
        memory.get(memoryKey)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = cachedFile(context, trackUri) ?: return@withContext null
            decodeFile(file, px)?.also { memory.put(memoryKey, it) }
        }
    }

    /** The cached JPEG for a track, extracting it first if needed; null when there is no cover. */
    suspend fun cachedFile(context: Context, trackUri: String): File? = withContext(Dispatchers.IO) {
        if (!isLocal(trackUri)) return@withContext null
        val fileUri = PlayableUri.split(trackUri).fileUri
        val key = sha1(fileUri)
        val lock = locks.getOrPut(key) { Mutex() }
        lock.withLock {
            val ref = refFile(context, key)
            if (ref.exists()) {
                val imageKey = runCatching { ref.readText().trim() }.getOrDefault(NONE)
                if (imageKey != NONE) {
                    val image = imageFile(context, imageKey)
                    if (image.exists()) {
                        image.setLastModified(System.currentTimeMillis())
                        return@withLock image
                    }
                } else if (System.currentTimeMillis() - ref.lastModified() < RETRY_NONE_MS) {
                    return@withLock null
                }
            }
            val bytes = embedded(context, fileUri) ?: folderPicture(context, trackUri, fileUri)
            val bitmap = bytes?.let { decodeBytes(it, MAX_PX) }
            ref.parentFile?.mkdirs()
            if (bitmap == null) {
                ref.writeText(NONE)
                return@withLock null
            }
            val imageKey = sha1(bytes)
            val image = imageFile(context, imageKey)
            if (!image.exists()) {
                image.parentFile?.mkdirs()
                val tmp = File(image.path + ".tmp")
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                tmp.renameTo(image)
                prune(context)
            }
            ref.writeText(imageKey)
            image
        }
    }

    /** Forgets cached covers of a track (e.g. after the library found a new folder picture). */
    fun forget(context: Context, trackUri: String) {
        refFile(context, fileKey(trackUri)).delete()
    }

    private fun embedded(context: Context, fileUri: String): ByteArray? = try {
        context.contentResolver.openFileDescriptor(Uri.parse(fileUri), "r")?.use { NativeTags.picture(it.fd) }
    } catch (_: Exception) {
        null
    }

    private suspend fun folderPicture(context: Context, trackUri: String, fileUri: String): ByteArray? {
        val indexed = runCatching { MusicDatabase.getDatabase(context).libraryDao().byUri(trackUri)?.folderCover }.getOrNull()
        val picture = indexed?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: SafTreeScanner.folderPictureFor(context.contentResolver, Uri.parse(fileUri))
            ?: return null
        return try {
            context.contentResolver.openInputStream(picture)?.use { input ->
                val bytes = input.readBytes()
                bytes.takeIf { it.size <= MAX_SOURCE_BYTES }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBytes(bytes: ByteArray, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, px) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return scaleDown(decoded, px)
    }

    private fun decodeFile(file: File, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, px) }
        return BitmapFactory.decodeFile(file.path, options)?.let { scaleDown(it, px) }
    }

    private fun sampleSize(width: Int, height: Int, px: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= px) sample *= 2
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, px: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= px) return bitmap
        val scale = px.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    /** Keeps the picture cache under its budget, least recently used first. */
    private fun prune(context: Context) {
        val images = File(root(context), "img").listFiles()?.filter { it.isFile } ?: return
        var total = images.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in images.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES * 3 / 4) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun sha1(text: String): String = sha1(text.toByteArray(Charsets.UTF_8))

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
