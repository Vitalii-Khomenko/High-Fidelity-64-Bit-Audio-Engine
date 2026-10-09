package com.aiproject.musicplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.aiproject.musicplayer.playback.PlaybackService
import com.aiproject.musicplayer.playback.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackServiceInstrumentedTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    @After
    fun stopService() {
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    private fun bind(): PlaybackService =
        (serviceRule.bindService(Intent(context, PlaybackService::class.java)) as PlaybackService.LocalBinder).service

    @Test
    fun notificationHasTransportActions() {
        val service = bind()
        var titles = emptyList<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.setQueue(listOf(Track("content://missing/1", "Instrumentation Track")), 0, false)
            titles = service.buildNotification().actions.map { it.title.toString() }
        }
        assertEquals(
            listOf(context.getString(R.string.action_previous), context.getString(R.string.action_play), context.getString(R.string.action_next)),
            titles,
        )
    }

    @Test
    fun queueCommandsUpdateState() {
        val service = bind()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.setQueue(List(3) { Track("content://missing/$it", "T$it") }, 1, false)
            assertEquals(1, service.state.value.currentIndex)
            assertEquals(1, service.addTracks(listOf(Track("content://missing/1", "dup"), Track("content://missing/9", "new"))))
            service.removeAt(0)
            assertEquals(3, service.state.value.tracks.size)
            assertEquals("content://missing/1", service.state.value.current?.uri)
            assertFalse(service.state.value.isPlaying)
            service.clearQueue()
            assertEquals(0, service.state.value.tracks.size)
        }
    }
}
