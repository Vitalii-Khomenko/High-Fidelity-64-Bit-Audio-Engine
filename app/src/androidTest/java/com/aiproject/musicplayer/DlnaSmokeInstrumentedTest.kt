package com.aiproject.musicplayer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiproject.musicplayer.library.DlnaDiscovery
import com.aiproject.musicplayer.library.DlnaPlaybackCache
import com.aiproject.musicplayer.library.DlnaServer
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DlnaSmokeInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()

    @Before
    fun startServer() { server.start() }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun browseParsesMockContentDirectoryResponse() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                      <s:Body>
                        <Result>&lt;DIDL-Lite xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot;&gt;
                          &lt;item&gt;
                            &lt;dc:title&gt;Mock Track&lt;/dc:title&gt;
                            &lt;res duration=&quot;0:00:42.250&quot;&gt;${server.url("/audio/mock.flac")}&lt;/res&gt;
                          &lt;/item&gt;
                        &lt;/DIDL-Lite&gt;</Result>
                      </s:Body>
                    </s:Envelope>
                    """.trimIndent()
                )
        )

        val tracks = DlnaDiscovery.browse(
            DlnaServer(
                friendlyName = "Mock",
                location = server.url("/device.xml").toString(),
                controlUrl = server.url("/ctl").toString(),
            )
        ).tracks

        assertEquals(1, tracks.size)
        assertEquals("Mock Track", tracks.first().title)
        assertEquals(42_250L, tracks.first().durationMs)
    }

    @Test
    fun playbackCacheDownloadsRemoteTrackToLocalFile() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("audio-bytes")
        )

        val cacheDir = File(context.cacheDir, "dlna-smoke-${System.nanoTime()}")
        val resolved = DlnaPlaybackCache.resolvePlaybackUri(
            Uri.parse(server.url("/track.flac").toString()),
            cacheDir,
        )

        assertEquals("file", resolved.scheme)
        val cachedFile = File(requireNotNull(resolved.path))
        assertTrue(cachedFile.exists())
        assertEquals("audio-bytes", cachedFile.readText())
    }
}
