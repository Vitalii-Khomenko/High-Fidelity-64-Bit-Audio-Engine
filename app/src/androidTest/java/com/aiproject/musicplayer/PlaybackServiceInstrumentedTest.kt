package com.aiproject.musicplayer

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun grantNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    @After
    fun stopGradleServiceArtifacts() {
        context.stopService(Intent(context, PlaybackService::class.java))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.cancelAll()
        }
    }

    @Test
    fun serviceShowsMediaStyleNotificationWithExpectedActions() {
        val service = bindService()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.showNotification("Instrumentation Track", false)
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel("MusicPlayerPro")
        assertNotNull(channel)

        val notification = waitForNotification(notificationManager)
        assertEquals("Instrumentation Track", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("Previous", "Play", "Next"), notification.actions.map { it.title.toString() })
    }

    @Test
    fun onStartCommandRemainsNotSticky() {
        val service = bindService()
        val result = service.onStartCommand(null, 0, 0)
        assertEquals(Service.START_NOT_STICKY, result)
    }

    private fun bindService(): PlaybackService {
        val binder = serviceRule.bindService(Intent(context, PlaybackService::class.java))
            as PlaybackService.LocalBinder
        return binder.getService()
    }

    private fun waitForNotification(notificationManager: NotificationManager): Notification {
        repeat(20) {
            val notification = notificationManager.activeNotifications
                .firstOrNull { it.id == 1 }
                ?.notification
            if (notification != null) {
                return notification
            }
            Thread.sleep(100)
        }
        throw AssertionError("Expected foreground notification was not posted")
    }
}
