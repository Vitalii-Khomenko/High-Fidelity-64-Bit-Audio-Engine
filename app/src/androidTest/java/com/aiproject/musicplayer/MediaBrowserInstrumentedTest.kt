package com.aiproject.musicplayer

import android.content.ComponentName
import android.content.Context
import android.support.v4.media.MediaBrowserCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aiproject.musicplayer.playback.MediaId
import com.aiproject.musicplayer.playback.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Connects like Android Auto does and reads the top of the browse tree. */
@RunWith(AndroidJUnit4::class)
class MediaBrowserInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun browserSeesQueuePlaylistsAndFolders() {
        val loaded = CountDownLatch(1)
        var children: List<MediaBrowserCompat.MediaItem> = emptyList()
        lateinit var browser: MediaBrowserCompat
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            browser = MediaBrowserCompat(
                context,
                ComponentName(context, PlaybackService::class.java),
                object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() {
                        browser.subscribe(browser.root, object : MediaBrowserCompat.SubscriptionCallback() {
                            override fun onChildrenLoaded(parentId: String, items: List<MediaBrowserCompat.MediaItem>) {
                                children = items
                                loaded.countDown()
                            }
                        })
                    }
                },
                null,
            )
            browser.connect()
        }
        assertTrue(loaded.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf(MediaId.Queue, MediaId.Playlists, MediaId.Folders),
            children.map { MediaId.parse(it.mediaId) },
        )
        assertTrue(children.all { it.isBrowsable })
        InstrumentationRegistry.getInstrumentation().runOnMainSync { browser.disconnect() }
    }
}
