package com.aiproject.musicplayer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.os.Process
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.MediaBrowserServiceCompat
import androidx.media.VolumeProviderCompat
import androidx.media.session.MediaButtonReceiver
import com.aiproject.musicplayer.AudioEngine
import com.aiproject.musicplayer.MainActivity
import com.aiproject.musicplayer.PlayerWidget
import com.aiproject.musicplayer.R
import com.aiproject.musicplayer.dlna.RendererHost
import com.aiproject.musicplayer.dlna.RendererProtocol
import com.aiproject.musicplayer.dlna.RendererServer
import com.aiproject.musicplayer.dlna.RendererStatus
import com.aiproject.musicplayer.dlna.TransportState
import com.aiproject.musicplayer.library.CoverArt
import com.aiproject.musicplayer.library.CoverProvider
import com.aiproject.musicplayer.library.DlnaPlaybackCache
import com.aiproject.musicplayer.library.LibraryFolders
import com.aiproject.musicplayer.library.LibraryIndex
import com.aiproject.musicplayer.library.MediaStoreScanner
import com.aiproject.musicplayer.library.PlaylistOrdering
import com.aiproject.musicplayer.library.SafTreeScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Owns playback: the native engine, the queue, audio focus, the media session
 * and the notification. The UI binds to it and observes [state]/[position];
 * notification buttons, headset keys and Bluetooth controls work the same way
 * whether or not the UI exists.
 *
 * All queue/state mutation happens on the main thread. Engine transport calls
 * run in order on a single background thread ([engineDispatcher]).
 */
class PlaybackService : MediaBrowserServiceCompat(), PlayerCommands {

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engineExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "engine-control") }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()
    // Loudness analysis decodes whole files: one at a time, at low priority.
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "loudness")
    }
    private val analysisDispatcher = analysisExecutor.asCoroutineDispatcher()

    private lateinit var engine: AudioEngine
    private lateinit var store: PlayerStore
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var browseTree: BrowseTree
    private lateinit var library: LibraryIndex
    private var libraryJob: Job? = null
    private var libraryProgress: LibraryIndex.Progress? = null
    private var enrichJob: Job? = null
    private val probed = HashSet<String>()          // queue tracks already completed (or tried)
    private lateinit var loudness: LoudnessAnalyzer
    private var analysisJob: Job? = null            // upcoming queue tracks
    private var libraryAnalysisJob: Job? = null
    private var analysisProgress: Pair<Int, Int>? = null
    // DLNA renderer
    private var renderer: RendererServer? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var rendererStatus = RendererStatus(TransportState.NO_MEDIA, "", "", "", "", 0, 0, 100, false)
    private var rendererUri = ""                    // last URI a control point set, with its DIDL metadata
    private var rendererMetadata = ""
    private var rendererNext: Pair<String, String>? = null
    private var rendererStopped = false
    private var muted = false
    private var art: Bitmap? = null                 // cover of the current track
    private var artUri: String? = null
    private var artJob: Job? = null
    private var publishedQueue: List<Track>? = null     // what the session queue currently shows
    private var publishedWindow = 0 to 0
    private var lastError: String? = null               // shown by Android Auto until the next start
    private val queue = PlaybackQueue()
    private val focusPolicy = AudioFocusPolicy()
    private var focusRequest: AudioFocusRequest? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _position = MutableStateFlow(PlaybackPosition())
    val position: StateFlow<PlaybackPosition> = _position.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Short user-facing notices (track could not be opened, ...). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var settings = PlayerSettings()
    private var playedUris: Set<String> = emptySet()

    // Playback bookkeeping (main thread).
    private var wantPlaying = false
    private var loadedUri: String? = null        // track currently loaded in the engine
    private var pendingStartMs = 0L              // where the next load starts
    private var preloadedUri: String? = null     // queued in the engine for gapless
    private var preloadTargetUri: String? = null // being opened by preloadJob
    // Last queued track, kept after invalidation: if the engine had already
    // switched to it audibly, the gapless event still names the right track.
    private var lastPreloadedUri: String? = null
    private var loadJob: Job? = null
    private var preloadJob: Job? = null
    private var monitorJob: Job? = null
    private var idleStopJob: Job? = null
    private var sleepJob: Job? = null
    private var sleepDeadline = 0L
    private var sleepFactor = 1.0
    private var duckFactor = 1.0

    // Bit-perfect USB output (Android 14+); null on older versions.
    private var bitPerfect: BitPerfectOutput? = null
    private var bitPerfectAvailable = false
    private var signalPath: SignalPath? = null
    private var lastProcessedCount = 0
    private var processedAt = Long.MIN_VALUE / 2
    // While the output is direct the system volume does nothing: the keys drive the engine volume.
    private var remoteVolume: VolumeProviderCompat? = null
    private val usbCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onUsbChanged(added)
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onUsbChanged(removed)
    }
    private var consecutiveFailures = 0
    private var pendingTransport = 0             // engine calls queued but not yet run
    private var isForeground = false
    private var lastBookmarkSave = 0L
    private var importing: String? = null
    private var activeImports = 0

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && wantPlaying) pause()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (focusPolicy.onFocusChange(change, wantPlaying)) {
            AudioFocusPolicy.Action.PAUSE -> pauseInternal(keepFocus = true)
            AudioFocusPolicy.Action.PAUSE_AND_FORGET -> pauseInternal(keepFocus = false)
            AudioFocusPolicy.Action.DUCK -> { duckFactor = AudioFocusPolicy.DUCK_FACTOR; applyVolume() }
            AudioFocusPolicy.Action.UNDUCK -> { duckFactor = 1.0; applyVolume() }
            AudioFocusPolicy.Action.RESUME -> { duckFactor = 1.0; applyVolume(); play() }
            AudioFocusPolicy.Action.NONE -> Unit
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        store = PlayerStore(this)
        engine = AudioEngine()
        createNotificationChannel()
        setupSession()
        sessionToken = session.sessionToken   // lets Android Auto and other browsers connect
        browseTree = BrowseTree(this)
        library = LibraryIndex(this)
        loudness = LoudnessAnalyzer(this)
        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        restoreState()
        setupBitPerfect()
        startMonitor()
        if (settings.renderer) startRenderer()
        // First start after an update (or a cleared index): build the library in the background.
        scope.launch {
            val empty = runCatching { library.count().first() == 0 }.getOrDefault(false)
            if (empty && LibraryFolders.load(this@PlaybackService).isNotEmpty()) updateLibrary()
        }
    }

    /** Media browsers (Android Auto) get the browser binder; our own UI gets [LocalBinder]. */
    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    // ── Media browser (Android Auto) ────────────────────────────────────────

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        if (isTrustedBrowser(clientPackageName, clientUid)) {
            BrowserRoot(MediaId.Root.encode(), BrowseTree.rootExtras())
        } else {
            // Others may still control playback through the session, but not read the library.
            BrowserRoot(MediaId.Empty.encode(), null)
        }

    private fun isTrustedBrowser(packageName: String, uid: Int): Boolean =
        uid == Process.myUid() || uid == Process.SYSTEM_UID || packageName in TRUSTED_BROWSERS

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        when (val id = MediaId.parse(parentId)) {
            MediaId.Root -> result.sendResult(browseTree.rootChildren().toMutableList())
            MediaId.Queue -> result.sendResult(browseTree.queueChildren(queue.tracks, queue.currentIndex).toMutableList())
            MediaId.Playlists, MediaId.Folders, is MediaId.Playlist, is MediaId.Folder,
            MediaId.Albums, MediaId.Artists, is MediaId.Album, is MediaId.Artist -> {
                result.detach()
                scope.launch {
                    val items = try {
                        withContext(Dispatchers.IO) {
                            when (id) {
                                MediaId.Playlists -> browseTree.playlistsChildren()
                                MediaId.Folders -> browseTree.foldersChildren()
                                is MediaId.Playlist -> browseTree.playlistChildren(id.id)
                                is MediaId.Folder -> browseTree.folderChildren(contentResolver, id)
                                MediaId.Albums -> browseTree.albumsChildren()
                                MediaId.Artists -> browseTree.artistsChildren()
                                is MediaId.Album -> browseTree.albumChildren(id.key)
                                is MediaId.Artist -> browseTree.artistChildren(id.key)
                                else -> emptyList()
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    result.sendResult(items.toMutableList())
                }
            }
            else -> result.sendResult(mutableListOf())
        }
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaItem>>) {
        val q = MediaSearch.normalize(query)
        val items = queue.tracks.withIndex()
            .filter { (_, t) -> q.isNotEmpty() && MediaSearch.normalize(t.title).contains(q) }
            .take(50)
            .map { (i, t) -> MediaItem(browseTree.description(MediaId.QueueTrack(i, t.uri), t), MediaItem.FLAG_PLAYABLE) }
        result.sendResult(items.toMutableList())
    }

    /** Plays a browse-tree item chosen in the car. */
    private fun playMediaId(mediaId: String?) {
        when (val id = MediaId.parse(mediaId)) {
            is MediaId.QueueTrack -> {
                val index = if (queue.tracks.getOrNull(id.index)?.uri == id.uri) id.index
                else queue.tracks.indexOfFirst { it.uri == id.uri }
                if (index >= 0) playIndex(index)
            }
            is MediaId.Playlist -> playPlaylist(id.id, 0)
            is MediaId.PlaylistTrack -> playPlaylist(id.playlistId, id.index)
            is MediaId.Folder -> importFolder(id.treeUri, id.documentId, id.label, play = true)
            is MediaId.FolderTrack -> scope.launch {
                val tracks = runCatching {
                    withContext(Dispatchers.IO) { browseTree.folderTracks(contentResolver, id.treeUri, id.documentId, id.label) }
                }.getOrDefault(emptyList())
                if (id.index in tracks.indices) setQueue(tracks, id.index, true) else reportError(id.label)
            }
            is MediaId.Album -> playLibrary { browseTree.albumTracks(id.key) }
            is MediaId.AlbumTrack -> playLibrary(id.index) { browseTree.albumTracks(id.key) }
            is MediaId.Artist -> playLibrary { browseTree.artistTracks(id.key) }
            MediaId.Queue -> play()
            else -> Unit
        }
    }

    private fun playLibrary(index: Int = 0, load: suspend () -> List<Track>) {
        scope.launch {
            val tracks = runCatching { withContext(Dispatchers.IO) { load() } }.getOrDefault(emptyList())
            if (index in tracks.indices) setQueue(tracks, index, true)
        }
    }

    private fun playPlaylist(playlistId: Int, index: Int) {
        scope.launch {
            val (tracks, shuffle) = runCatching {
                withContext(Dispatchers.IO) { browseTree.playlistTracks(playlistId) to browseTree.playlistShuffle(playlistId) }
            }.getOrDefault(emptyList<Track>() to false)
            if (index !in tracks.indices) return@launch
            setQueue(tracks, index, true)
            if (shuffle != queue.shuffle) setShuffle(shuffle)
        }
    }

    /** Voice: "play <query> on HiFi Player". An empty query resumes playback. */
    fun playFromSearch(query: String?) {
        if (query.isNullOrBlank()) {
            play()
            return
        }
        val inQueue = MediaSearch.bestMatch(query, queue.tracks.map { it.title })
        if (inQueue >= 0) {
            playIndex(inQueue)
            return
        }
        scope.launch {
            val playlist = runCatching { withContext(Dispatchers.IO) { browseTree.playlistByName(query) } }.getOrNull()
            if (playlist != null) {
                playPlaylist(playlist.first, 0)
                return@launch
            }
            val fromLibrary = runCatching { withContext(Dispatchers.IO) { browseTree.searchLibrary(query) } }.getOrDefault(emptyList())
            if (fromLibrary.isNotEmpty()) {
                setQueue(fromLibrary, 0, true)
                return@launch
            }
            val folder = runCatching { withContext(Dispatchers.IO) { browseTree.matchingFolder(query) } }.getOrNull()
            if (folder != null) importFolder(folder.treeUri, folder.documentId, folder.label, play = true)
            else reportError(query)
        }
    }

    private fun reportError(subject: String) {
        val message = getString(R.string.error_cannot_play, subject)
        lastError = message
        _messages.tryEmit(message)
        updateSessionState()
    }

    /** Mirrors the queue (a window around the current track) into the session for the car's queue view. */
    private fun publishSessionQueue() {
        val tracks = queue.tracks
        val window = queueWindow(tracks.size, queue.currentIndex)
        if (tracks === publishedQueue && window == publishedWindow) return
        val tracksChanged = tracks !== publishedQueue
        publishedQueue = tracks
        publishedWindow = window
        session.setQueue(
            (window.first until window.second).map { i ->
                android.support.v4.media.session.MediaSessionCompat.QueueItem(
                    browseTree.description(MediaId.QueueTrack(i, tracks[i].uri), tracks[i]), i.toLong(),
                )
            },
        )
        session.setQueueTitle(getString(R.string.queue))
        if (tracksChanged) notifyChildrenChanged(MediaId.Queue.encode())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started with startForegroundService(): promote at once, as required.
        // Stay foreground while the command (e.g. a media button "play") is
        // handled: on Android 15+ audio focus is only granted to a foreground app.
        if (!isForeground) promoteToForeground()
        // Only media-button intents are acted on: the service is exported for
        // media browsers, so other apps can start it with arbitrary intents.
        MediaButtonReceiver.handleIntent(session, intent)
        if (!wantPlaying && loadJob?.isActive != true && isForeground) leaveForeground(removeNotification = false)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps music playing; an idle player goes away.
        if (!wantPlaying) stopSelf()
    }

    override fun onDestroy() {
        stopRenderer()
        saveResumePoint()
        scope.cancel()
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        bitPerfect?.let { output ->
            audioManager.unregisterAudioDeviceCallback(usbCallback)
            output.enabled = false
            output.release()   // hand the DAC back to the system mixer
        }
        abandonFocus()
        session.isActive = false
        session.release()
        // Release on the engine thread so it runs after any queued transport call.
        engineExecutor.execute { engine.release() }
        engineExecutor.shutdown()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    // ── Queue commands (UI) ──────────────────────────────────────────────────

    /** Replaces the queue and starts [startIndex]. */
    override fun setQueue(tracks: List<Track>, startIndex: Int, play: Boolean) {
        rememberCurrentPosition()
        queue.replace(tracks, -1)
        queue.repeat = settings.repeat
        queueChanged(saveTracks = true)
        if (startIndex in queue.tracks.indices) {
            if (play) playIndex(startIndex) else selectIndex(startIndex)
        } else {
            stopEngine()
        }
    }

    /** Adds tracks not already queued (sorted with the current sort mode). Returns the count added. */
    override fun addTracks(tracks: List<Track>): Int {
        val added = queue.append(tracks)
        if (added > 0) {
            queue.sort(PlaylistOrdering.comparator(settings.sortMode))
            queueChanged(saveTracks = true)
        }
        return added
    }

    override fun removeAt(index: Int) {
        val wasCurrent = queue.removeAt(index)
        if (wasCurrent) {
            val next = queue.peekNext(auto = false)
            if (next >= 0 && wantPlaying) {
                queue.advance(auto = false)
                startCurrent(startMs = bookmarkFor(queue.current))
            } else {
                stopEngine()
            }
        }
        queueChanged(saveTracks = true)
    }

    override fun clearQueue() {
        stopEngine()
        queue.clear()
        store.saveResumePoint(null, 0)
        queueChanged(saveTracks = true)
    }

    override fun sortQueue(mode: SortMode) {
        updateSettings(settings.copy(sortMode = mode))
        queue.sort(PlaylistOrdering.comparator(mode))
        queueChanged(saveTracks = true)
    }

    override fun setShuffle(enabled: Boolean) {
        queue.setShuffle(enabled)
        queueChanged(saveTracks = false)
    }

    override fun cycleRepeat() {
        val next = settings.repeat.next()
        queue.repeat = next
        updateSettings(settings.copy(repeat = next))
        queueChanged(saveTracks = false)
    }

    /** Replaces queued tracks by URI (completed tags, durations) without disturbing playback. */
    private fun applyTracks(updates: Map<String, Track>) {
        if (updates.isEmpty()) return
        val updated = queue.tracks.map { t -> updates[t.uri] ?: t }
        queue.restore(updated, queue.currentIndex, queue.shuffle, queue.playOrder())
        queueChanged(saveTracks = true)
        if (queue.current?.uri in updates) updateSessionMetadata()
    }

    /** Reads tags and durations of queued tracks that lack them, in small batches. */
    private fun scheduleEnrichment() {
        if (enrichJob?.isActive == true) return
        val pending = queue.tracks.filter { it.uri !in probed && TrackProbe.needs(it) }
        if (pending.isEmpty()) return
        enrichJob = scope.launch {
            val keepTitle = settings.contentMode == ContentMode.BOOKS
            for (batch in pending.chunked(16)) {
                val found = withContext(Dispatchers.IO) {
                    batch.mapNotNull { track ->
                        probed += track.uri
                        runCatching { TrackProbe.complete(this@PlaybackService, library, track, keepTitle) }
                            .getOrNull()?.let { track.uri to it }
                    }.toMap()
                }
                applyTracks(found)
            }
            enrichJob = null
            scheduleEnrichment()   // tracks added meanwhile
        }
    }

    // ── Transport commands ───────────────────────────────────────────────────

    override fun playIndex(index: Int) {
        if (index !in queue.tracks.indices) return
        consecutiveFailures = 0
        rememberCurrentPosition()
        queue.jumpTo(index)
        startCurrent(startMs = bookmarkFor(queue.current))
    }

    override fun togglePlayPause() = if (wantPlaying) pause() else play()

    fun play() {
        val track = queue.current
        if (track == null) {
            if (queue.size > 0) {
                queue.advance(auto = false)
                startCurrent(startMs = bookmarkFor(queue.current))
            }
            return
        }
        focusPolicy.onUserAction()
        val engineState = engine.state()
        if (loadedUri == track.uri && engineState == AudioEngine.State.ENDED) {
            // Paused right at the end: continue with what comes next.
            wantPlaying = true
            onTrackEnded()
        } else if (loadedUri == track.uri &&
            (engineState == AudioEngine.State.PAUSED || engineState == AudioEngine.State.PLAYING)
        ) {
            // PLAYING here means a pause is still queued: play() lands after it.
            // Foreground first: Android 15+ refuses focus to a background service.
            enterForeground()
            if (!requestFocus()) {
                leaveForeground(removeNotification = false)
                return
            }
            wantPlaying = true
            publish()
            pendingTransport++
            scope.launch {
                val ok = try {
                    withContext(engineDispatcher) { engine.play() }
                } finally {
                    pendingTransport--
                }
                if (!ok) failCurrent(track, auto = false)
                publish()
            }
        } else {
            startCurrent(startMs = pendingStartMs)
        }
    }

    fun pause() {
        focusPolicy.onUserAction()
        pauseInternal(keepFocus = false)
    }

    override fun stop() {
        focusPolicy.onUserAction()
        rememberCurrentPosition()
        wantPlaying = false
        loadJob?.cancel()
        transport { engine.stop() }
        pendingStartMs = 0L
        _position.value = _position.value.copy(positionMs = 0L)
        abandonFocus()
        publish()
        leaveForeground(removeNotification = true)
        stopSelf()
    }

    override fun next() {
        rememberCurrentPosition()
        if (queue.advance(auto = false) < 0) return
        if (wantPlaying) startCurrent(startMs = bookmarkFor(queue.current)) else selectCurrent()
    }

    override fun previous() {
        if (_position.value.positionMs > RESTART_THRESHOLD_MS || queue.peekPrevious() < 0) {
            seekTo(0L)
            return
        }
        rememberCurrentPosition()
        queue.retreat()
        if (wantPlaying) startCurrent(startMs = bookmarkFor(queue.current)) else selectCurrent()
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        _position.value = _position.value.copy(positionMs = target)
        if (loadedUri != null && loadedUri == queue.current?.uri) {
            transport { engine.seekTo(target) }
            invalidatePreload()
        } else {
            pendingStartMs = target
        }
        updateSessionState()
    }

    // ── Sound settings ───────────────────────────────────────────────────────

    override fun setVolume(volume: Float) {
        updateSettings(settings.copy(volume = volume.coerceIn(0f, 1f)))
        applyVolume()
        remoteVolume?.currentVolume = volumePercent()
    }

    override fun setSpeed(speed: Float) {
        val clamped = PlaybackSpeed.clamp(speed)
        updateSettings(settings.copy(speed = clamped))
        engine.setSpeed(clamped.toDouble())
        updateSessionState()
    }

    override fun setSpeedMode(mode: SpeedMode) {
        updateSettings(settings.copy(speedMode = mode))
        engine.setSpeedMode(mode.id)
    }

    override fun setEq(eq: EqSettings) {
        val normalized = eq.normalized()
        updateSettings(settings.copy(eq = normalized))
        applyEq(normalized)
    }

    override fun setReplayGain(mode: ReplayGainMode) {
        if (mode == settings.replayGain) return
        updateSettings(settings.copy(replayGain = mode))
        // Takes effect from the next load; refresh the queued next track now.
        invalidatePreload()
    }

    override fun setCrossfeed(mode: CrossfeedMode) {
        updateSettings(settings.copy(crossfeed = mode))
        engine.setCrossfeed(mode)
    }

    override fun setLimiter(enabled: Boolean) {
        updateSettings(settings.copy(limiter = enabled))
        engine.setLimiter(enabled)
    }

    override fun setBitPerfect(enabled: Boolean) {
        updateSettings(settings.copy(bitPerfect = enabled))
        val output = bitPerfect ?: return
        output.enabled = enabled
        engineExecutor.execute {
            engine.reopenOutput()
            if (!enabled) output.release()   // also when no stream was open
        }
    }

    // ── Bit-perfect USB output ───────────────────────────────────────────────

    private fun setupBitPerfect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val output = BitPerfectOutput(audioManager).also { it.enabled = settings.bitPerfect }
        bitPerfect = output
        bitPerfectAvailable = output.capableDevice() != null
        engineExecutor.execute { engine.setDirectOutput(output) }
        audioManager.registerAudioDeviceCallback(usbCallback, null)
    }

    private fun onUsbChanged(devices: Array<out AudioDeviceInfo>) {
        val output = bitPerfect ?: return
        if (devices.none { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }) return
        bitPerfectAvailable = output.capableDevice() != null
        // A DAC plugged in while playing: reopen so the stream goes direct (or back to the mixer).
        if (settings.bitPerfect) engineExecutor.execute { engine.reopenOutput() }
        publish()
    }

    /** Re-evaluates what reaches the device; publishes only on a change. */
    private fun updateSignalPath() {
        val format = if (loadedUri != null) engine.format() else null
        val now = SystemClock.elapsedRealtime()
        if (format != null && format.processedSamples != lastProcessedCount) {
            lastProcessedCount = format.processedSamples
            processedAt = now
        }
        val path = format?.let { SignalPath.of(it, settings, engineVolume(), now - processedAt < PROCESSED_HOLD_MS) }
        if (path == signalPath) return
        signalPath = path
        updateRemoteVolume()
        publish()
    }

    private fun updateRemoteVolume() {
        val direct = signalPath.let { it != null && it.kind != SignalPath.Kind.MIXED }
        if (direct && remoteVolume == null) {
            val provider = object : VolumeProviderCompat(VOLUME_CONTROL_ABSOLUTE, 100, volumePercent()) {
                override fun onSetVolumeTo(volume: Int) = setVolume(volume / 100f)
                override fun onAdjustVolume(direction: Int) {
                    if (direction != 0) setVolume(settings.volume + direction * VOLUME_KEY_STEP)
                }
            }
            remoteVolume = provider
            session.setPlaybackToRemote(provider)
        } else if (!direct && remoteVolume != null) {
            remoteVolume = null
            session.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
        }
    }

    private fun volumePercent() = (settings.volume * 100f).roundToInt().coerceIn(0, 100)

    override fun setAutoAnalyze(enabled: Boolean) {
        updateSettings(settings.copy(autoAnalyze = enabled))
        if (enabled) scheduleAnalysis() else analysisJob?.cancel()
    }

    override fun analyzeLibrary() {
        if (libraryAnalysisJob?.isActive == true) return
        libraryAnalysisJob = scope.launch {
            try {
                val pending = withContext(Dispatchers.IO) { loudness.libraryPending() }
                pending.forEachIndexed { i, uri ->
                    analysisProgress = i to pending.size
                    publish()
                    withContext(analysisDispatcher) { loudness.analyze(uri) }
                }
            } finally {
                analysisProgress = null
                libraryAnalysisJob = null
                publish()
            }
        }
    }

    override fun cancelAnalysis() {
        libraryAnalysisJob?.cancel()
    }

    /** Measures the current and the next two tracks if they have no ReplayGain tags, one at a time. */
    private fun scheduleAnalysis() {
        if (!settings.autoAnalyze || analysisJob?.isActive == true) return
        val upcoming = buildList {
            val next = queue.peekNext(auto = true)
            queue.tracks.getOrNull(next)?.let(::add)
            queue.current?.let(::add)
        }.map { it.uri }.distinct()
        if (upcoming.isEmpty()) return
        analysisJob = scope.launch {
            for (uri in upcoming) {
                val needed = withContext(Dispatchers.IO) { loudness.needsAnalysis(uri) }
                if (!needed) continue
                withContext(analysisDispatcher) { loudness.analyze(uri) }
                // A measured next track replaces the pre-loaded one so its gain applies.
                if (uri == preloadedUri) invalidatePreload()
            }
        }
    }

    override fun setRenderer(enabled: Boolean) {
        updateSettings(settings.copy(renderer = enabled))
        if (enabled) startRenderer() else stopRenderer()
        publish()
    }

    private fun rendererName(): String = "HiFi Player (${Build.MODEL})"

    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private var rendererStartJob: Job? = null

    /** A stable device id, so control points remember this renderer. */
    private fun rendererUdn(): String {
        val prefs = getSharedPreferences("dlna_renderer", MODE_PRIVATE)
        return prefs.getString("udn", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("udn", it).apply() }
    }

    private fun startRenderer() {
        if (renderer?.isRunning == true || rendererStartJob?.isActive == true) return
        val server = RendererServer(rendererHost, rendererName(), rendererUdn())
        rendererStartJob = scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { server.start() }.getOrDefault(false) }
            if (ok && settings.renderer) {
                renderer = server
                // Some phones drop multicast (SSDP searches) without a lock.
                multicastLock = runCatching {
                    (applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager)
                        ?.createMulticastLock("hifi-renderer")?.apply { setReferenceCounted(false); acquire() }
                }.getOrNull()
            } else {
                stopInBackground(server)
                if (settings.renderer) _messages.tryEmit(getString(R.string.renderer_no_network))
            }
            publish()
        }.also { job ->
            // Switched off while starting: the started server must not stay up.
            job.invokeOnCompletion { cause -> if (cause != null) stopInBackground(server) }
        }
    }

    private fun stopRenderer() {
        rendererStartJob?.cancel()
        rendererStartJob = null
        runCatching { if (multicastLock?.isHeld == true) multicastLock?.release() }
        multicastLock = null
        val server = renderer ?: return
        renderer = null
        stopInBackground(server)
    }

    /** stop() sends a goodbye over the network: never on the main thread, and also after the scope is gone. */
    private fun stopInBackground(server: RendererServer) {
        Thread({ runCatching { server.stop() } }, "dlna-renderer-stop").start()
    }

    // ── Renderer host: called on the renderer's threads ─────────────────────

    private fun onMain(block: () -> Unit) {
        main.post(block)
    }

    private fun rendererTrack(uri: String, metadata: String): Track {
        val info = RendererProtocol.parseDidl(metadata)
        val name = Uri.parse(uri).lastPathSegment?.substringBeforeLast('.').orEmpty()
        return Track(uri, info.title.ifBlank { name.ifBlank { uri } }, folder = getString(R.string.renderer_folder), artist = info.artist, album = info.album)
    }

    private val rendererHost = object : RendererHost {
        override fun status(): RendererStatus = rendererStatus.copy(positionMs = _position.value.positionMs)

        override fun setUri(uri: String, metadata: String) = onMain {
            rendererUri = uri
            rendererMetadata = metadata
            rendererNext = null
            rendererStopped = false
            // Like a hardware renderer: a new URI replaces what plays and keeps playing if it was.
            setQueue(listOf(rendererTrack(uri, metadata)), 0, wantPlaying)
        }

        override fun setNextUri(uri: String, metadata: String) = onMain {
            val current = queue.current ?: return@onMain
            if (uri.isEmpty()) {
                rendererNext = null
                queue.restore(listOf(current), 0, false, emptyList())
            } else {
                rendererNext = uri to metadata
                queue.restore(listOf(current, rendererTrack(uri, metadata)).distinctBy { it.uri }, 0, false, emptyList())
            }
            queueChanged(saveTracks = true)
        }

        override fun play() = onMain {
            rendererStopped = false
            this@PlaybackService.play()
        }

        override fun pause() = onMain { this@PlaybackService.pause() }

        override fun stop() = onMain {
            // Stopped, not shut down: the renderer and the service stay.
            pauseInternal(keepFocus = false)
            seekTo(0L)
            rendererStopped = true
            publish()
        }

        override fun seek(positionMs: Long) = onMain { seekTo(positionMs) }
        override fun next() = onMain { this@PlaybackService.next() }
        override fun previous() = onMain { this@PlaybackService.previous() }
        override fun setVolume(percent: Int) = onMain { this@PlaybackService.setVolume(percent / 100f) }

        override fun setMute(muted: Boolean) = onMain {
            this@PlaybackService.muted = muted
            applyVolume()
            publish()
        }
    }

    /** Snapshot for control points (read on the renderer's threads). */
    private fun updateRendererStatus() {
        if (renderer == null) return
        val track = queue.current
        val state = when {
            track == null -> TransportState.NO_MEDIA
            loadJob?.isActive == true -> TransportState.TRANSITIONING
            wantPlaying -> TransportState.PLAYING
            rendererStopped -> TransportState.STOPPED
            else -> TransportState.PAUSED
        }
        val uri = track?.uri.orEmpty()
        val metadata = if (uri == rendererUri && rendererMetadata.isNotEmpty()) rendererMetadata
        else track?.let { RendererProtocol.didl(it.uri, it.title, it.artist, it.album, it.durationMs) }.orEmpty()
        val next = queue.tracks.getOrNull(queue.peekNext(auto = true))
        rendererStatus = RendererStatus(
            transport = state,
            uri = uri,
            metadata = metadata,
            nextUri = next?.uri.orEmpty(),
            nextMetadata = if (next != null && next.uri == rendererNext?.first) rendererNext?.second.orEmpty() else "",
            positionMs = _position.value.positionMs,
            durationMs = _position.value.durationMs.coerceAtLeast(track?.durationMs ?: 0L),
            volume = (settings.volume * 100).toInt(),
            muted = muted,
        )
        renderer?.notifyChanged()
    }

    override fun setContentMode(mode: ContentMode) {
        updateSettings(settings.copy(contentMode = mode))
    }

    // ── Sleep timer ──────────────────────────────────────────────────────────

    override fun startSleepTimer(durationMs: Long) {
        cancelSleepTimer()
        if (durationMs <= 0L) return
        sleepDeadline = SystemClock.elapsedRealtime() + durationMs
        publish()
        sleepJob = scope.launch {
            while (isActive) {
                val left = sleepDeadline - SystemClock.elapsedRealtime()
                if (left <= 0L) break
                // Fade over the last 30 seconds.
                sleepFactor = (left / SLEEP_FADE_MS.toDouble()).coerceIn(0.0, 1.0)
                applyVolume()
                delay(if (left > SLEEP_FADE_MS) left - SLEEP_FADE_MS else 250L)
            }
            sleepDeadline = 0L
            pauseInternal(keepFocus = false)
            sleepFactor = 1.0
            applyVolume()
            publish()
        }
    }

    override fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        sleepDeadline = 0L
        sleepFactor = 1.0
        applyVolume()
        publish()
    }

    // ── Library import ───────────────────────────────────────────────────────

    override fun importFolder(treeUri: String, documentId: String?, label: String, play: Boolean) {
        runImport(label, scan = {
            val tree = Uri.parse(treeUri)
            SafTreeScanner.scanTracks(contentResolver, tree, documentId ?: DocumentsContract.getTreeDocumentId(tree), label)
        }) { tracks ->
            when {
                tracks.isEmpty() -> _messages.tryEmit(getString(R.string.no_tracks_in, label))
                play -> setQueue(tracks, 0, true)
                else -> _messages.tryEmit(getString(R.string.added_tracks, addTracks(tracks), label))
            }
        }
    }

    override fun importDeviceLibrary() {
        val label = getString(R.string.device)
        runImport(label, scan = { MediaStoreScanner.scan(contentResolver) }) { tracks ->
            _messages.tryEmit(getString(R.string.added_tracks, addTracks(tracks), label))
        }
    }

    override fun updateLibrary() {
        if (libraryJob?.isActive == true) return
        libraryJob = scope.launch {
            libraryProgress = LibraryIndex.Progress("", 0)
            publish()
            val accessible = LibraryFolders.accessibleUris(this@PlaybackService)
            val sources = LibraryFolders.load(this@PlaybackService).filter { it.uriString in accessible }
            try {
                library.update(sources) { progress ->
                    scope.launch {
                        libraryProgress = progress
                        publish()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _messages.tryEmit(getString(R.string.library_update_failed))
            } finally {
                libraryProgress = null
                libraryJob = null
                publish()
            }
        }
    }

    override fun forgetLibraryFolder(treeUri: String) {
        scope.launch {
            runCatching { library.remove(com.aiproject.musicplayer.library.LibraryFolderEntry(treeUri, "")) }
        }
    }

    /** Scans on IO in the service scope (sorted), then hands the result to [onDone] on the main thread. */
    private fun runImport(label: String, scan: () -> List<Track>, onDone: (List<Track>) -> Unit) {
        importing = label
        activeImports++
        publish()
        scope.launch {
            val tracks = try {
                withContext(Dispatchers.IO) { PlaylistOrdering.sortTracks(scan(), settings.sortMode) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            // Another scan may still be running; keep its indicator.
            if (--activeImports == 0) importing = null
            onDone(tracks)
            publish()
        }
    }

    /** Levels for the visualiser (reads native state, never blocks). */
    override fun readSpectrum(bands: FloatArray) = engine.spectrum(bands)

    // ── Internals: loading and transitions ───────────────────────────────────

    /** Runs an engine call in order on the engine thread; the monitor waits for it. */
    private fun transport(block: () -> Unit) {
        pendingTransport++
        scope.launch {
            try {
                withContext(engineDispatcher) { block() }
            } finally {
                pendingTransport--
            }
        }
    }

    private fun startCurrent(startMs: Long, auto: Boolean = false) {
        val track = queue.current ?: return
        idleStopJob?.cancel()
        invalidatePreload()
        // DLNA downloads for tracks that are neither current nor next are no longer needed.
        DlnaPlaybackCache.retainOnly(setOfNotNull(track.uri, queue.tracks.getOrNull(queue.peekNext(auto = true))?.uri))
        lastPreloadedUri = null
        loadJob?.cancel()
        wantPlaying = true
        loadedUri = null
        pendingStartMs = 0L
        _position.value = PlaybackPosition(startMs, track.durationMs)
        publish(loading = true)
        saveQueuePosition()
        enterForeground()  // before focus: Android 15+ grants focus only to foreground apps
        if (!requestFocus()) {
            wantPlaying = false
            pendingStartMs = startMs
            publish()
            leaveForeground(removeNotification = false)
            return
        }
        loadJob = scope.launch {
            val ok = try {
                val resolved = DlnaPlaybackCache.resolve(Uri.parse(track.uri), cacheDir)
                val playable = resolved.uri
                val measured = if (settings.replayGain != ReplayGainMode.OFF) {
                    withContext(Dispatchers.IO) { runCatching { loudness.fallbackFor(track.uri) }.getOrNull() }
                } else {
                    null
                }
                withContext(engineDispatcher) {
                    if (!engine.load(this@PlaybackService, playable, settings.replayGain, measured, resolved)) return@withContext false
                    if (startMs > 0L) engine.seekTo(startMs)
                    ensureActive()  // a newer request replaced this one: load, but do not start
                    engine.play()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            // Reaching here means no newer request cancelled this one.
            loadJob = null
            if (!ok) {
                failCurrent(track, auto)
                return@launch
            }
            consecutiveFailures = 0
            lastError = null
            loadedUri = track.uri
            onTrackStarted(track)
        }
    }

    /** Selects the current track without playing it; it loads on play(). */
    private fun selectCurrent() {
        loadJob?.cancel()
        invalidatePreload()
        if (wantPlaying) {
            // Selecting without playing ends playback: release focus and the foreground state.
            wantPlaying = false
            abandonFocus()
            leaveForeground(removeNotification = false)
        }
        loadedUri = null
        val track = queue.current
        pendingStartMs = bookmarkFor(track)
        _position.value = PlaybackPosition(pendingStartMs, track?.durationMs ?: 0L)
        transport { engine.stop() }
        saveQueuePosition()
        publish()
        updateSessionMetadata()
    }

    private fun selectIndex(index: Int) {
        queue.jumpTo(index)
        selectCurrent()
    }

    private fun onTrackStarted(track: Track) {
        val duration = engine.durationMs()
        if (duration > 0 && track.durationMs != duration) {
            fillDuration(track.uri, duration)
        }
        _position.value = PlaybackPosition(engine.positionMs(), duration)
        publish()
        updateSessionMetadata()
        updateNotification()
        scheduleAnalysis()
    }

    private fun failCurrent(track: Track, auto: Boolean) {
        consecutiveFailures++
        loadedUri = null
        lastError = getString(R.string.error_cannot_play, track.title)
        _messages.tryEmit(lastError!!)
        // Skip broken files during continuous playback, but never loop forever.
        if (auto && consecutiveFailures < MAX_SKIPS && queue.advance(auto = false) >= 0) {
            startCurrent(startMs = 0L, auto = true)
            return
        }
        // A failed open leaves the previous track loaded (and possibly still
        // playing): silence it so state, session and focus agree.
        wantPlaying = false
        transport { engine.stop() }
        abandonFocus()
        publish()
        leaveForeground(removeNotification = false)
    }

    private fun onTrackEnded() {
        val finished = queue.current
        finished?.let { markFinished(it) }
        val wasIndex = queue.currentIndex
        val next = queue.advance(auto = true)
        when {
            next < 0 -> {
                // End of the queue: stay on the last track, rewound and paused.
                wantPlaying = false
                pendingStartMs = 0L
                loadedUri = null
                _position.value = _position.value.copy(positionMs = 0L)
                store.saveResumePoint(null, 0L)
                abandonFocus()
                publish()
                leaveForeground(removeNotification = false)
            }
            next == wasIndex && loadedUri == finished?.uri -> {
                transport { engine.seekTo(0L); engine.play() }
            }
            else -> startCurrent(startMs = 0L, auto = true)
        }
    }

    private fun onGaplessAdvanced() {
        val finished = queue.current
        finished?.let { markFinished(it) }
        val target = preloadedUri ?: lastPreloadedUri
        preloadedUri = null
        lastPreloadedUri = null
        queue.advance(auto = true)
        if (target != null && queue.current?.uri != target) {
            val index = queue.tracks.indexOfFirst { it.uri == target }
            if (index >= 0) queue.jumpTo(index)
        }
        loadedUri = target ?: queue.current?.uri
        saveQueuePosition()
        queue.current?.let { onTrackStarted(it) }
    }

    private fun maybePreload(positionMs: Long, durationMs: Long) {
        if (preloadedUri != null || preloadJob?.isActive == true) return
        if (durationMs <= 0L || durationMs - positionMs > PRELOAD_WINDOW_MS) return
        val nextIndex = queue.peekNext(auto = true)
        val track = queue.tracks.getOrNull(nextIndex) ?: return
        val replayGain = settings.replayGain
        preloadTargetUri = track.uri
        preloadJob = scope.launch {
            val ok = try {
                val resolved = DlnaPlaybackCache.resolve(Uri.parse(track.uri), cacheDir)
                val playable = resolved.uri
                // loadNext only touches the engine's next-track slot; no need to
                // wait behind transport calls.
                withContext(Dispatchers.IO) {
                    val measured = if (replayGain != ReplayGainMode.OFF) runCatching { loudness.fallbackFor(track.uri) }.getOrNull() else null
                    engine.loadNext(this@PlaybackService, playable, replayGain, measured, resolved)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            preloadTargetUri = null
            if (!ok) return@launch
            if (queue.tracks.getOrNull(queue.peekNext(auto = true))?.uri == track.uri) {
                preloadedUri = track.uri
                lastPreloadedUri = track.uri
            } else {
                engine.clearNext()  // the queue changed while it was opening
            }
        }
    }

    private fun invalidatePreload() {
        // Cancelling the coroutine cannot interrupt a JNI open already running;
        // clearNext() bumps the native generation so that open is discarded.
        val inFlight = preloadJob?.isActive == true
        preloadJob?.cancel()
        preloadJob = null
        preloadTargetUri = null
        if (inFlight || preloadedUri != null) engine.clearNext()
        preloadedUri = null
    }

    private fun pauseInternal(keepFocus: Boolean) {
        if (!wantPlaying && loadJob?.isActive != true) return
        wantPlaying = false
        rememberCurrentPosition()
        if (loadJob?.isActive == true) {
            loadJob?.cancel()
            pendingStartMs = _position.value.positionMs
        }
        transport { engine.pause() }
        if (!keepFocus) abandonFocus()
        publish()
        leaveForeground(removeNotification = false)
    }

    private fun stopEngine() {
        wantPlaying = false
        loadJob?.cancel()
        invalidatePreload()
        loadedUri = null
        pendingStartMs = 0L
        transport { engine.stop() }
        _position.value = PlaybackPosition()
        abandonFocus()
        publish()
        leaveForeground(removeNotification = false)
    }

    // ── Monitor ──────────────────────────────────────────────────────────────

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                if (engine.consumeTrackAdvanced()) onGaplessAdvanced()
                updateSignalPath()
                val engineState = engine.state()
                val loaded = loadedUri != null && loadJob?.isActive != true && pendingTransport == 0
                if (loaded) {
                    val pos = engine.positionMs()
                    val dur = engine.durationMs()
                    _position.value = PlaybackPosition(pos, dur)
                    when (engineState) {
                        AudioEngine.State.PLAYING -> {
                            maybePreload(pos, dur)
                            periodicSave(pos)
                        }
                        AudioEngine.State.ENDED -> if (wantPlaying) onTrackEnded()
                        AudioEngine.State.ERROR -> if (wantPlaying) queue.current?.let { failCurrent(it, auto = true) }
                        else -> Unit
                    }
                }
                delay(if (wantPlaying) 200L else 750L)
            }
        }
    }

    private fun periodicSave(positionMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBookmarkSave < 5_000L) return
        lastBookmarkSave = now
        queue.current?.let { track ->
            store.saveResumePoint(track.uri, positionMs)
            if (settings.contentMode.remembersPosition && positionMs > MIN_BOOKMARK_MS) {
                store.saveBookmark(track.uri, positionMs)
            }
        }
    }

    // ── State, persistence ───────────────────────────────────────────────────

    private fun restoreState() {
        settings = store.loadSettings()
        playedUris = store.playedUris()
        val saved = store.loadQueue()
        queue.restore(saved.tracks, saved.index, saved.shuffle, saved.order)
        queue.repeat = settings.repeat
        val current = queue.current
        pendingStartMs = if (current != null && current.uri == saved.resumeUri) saved.resumePositionMs else bookmarkFor(current)
        _position.value = PlaybackPosition(pendingStartMs, current?.durationMs ?: 0L)
        engine.setSpeed(settings.speed.toDouble())
        engine.setSpeedMode(settings.speedMode.id)
        engine.setCrossfeed(settings.crossfeed)
        engine.setLimiter(settings.limiter)
        applyEq(settings.eq)
        applyVolume()
        publish()
        updateSessionMetadata()
    }

    private fun queueChanged(saveTracks: Boolean) {
        // A queued next track that is no longer next must not play.
        val nextUri = queue.tracks.getOrNull(queue.peekNext(auto = true))?.uri
        if ((preloadedUri != null && preloadedUri != nextUri) ||
            (preloadJob?.isActive == true && preloadTargetUri != nextUri)
        ) {
            invalidatePreload()
        }
        if (saveTracks) store.saveTracks(queue.tracks)
        saveQueuePosition()
        publish()
        updateSessionMetadata()
        scheduleEnrichment()
    }

    private fun saveQueuePosition() = store.savePosition(queue.currentIndex, queue.shuffle, queue.playOrder())

    private fun saveResumePoint() {
        val track = queue.current ?: return
        store.saveResumePoint(track.uri, _position.value.positionMs)
    }

    /** Stores bookmark/resume point of the track being left. */
    private fun rememberCurrentPosition() {
        val track = queue.current ?: return
        val pos = _position.value.positionMs
        store.saveResumePoint(track.uri, pos)
        if (settings.contentMode.remembersPosition && pos > MIN_BOOKMARK_MS) store.saveBookmark(track.uri, pos)
    }

    private fun bookmarkFor(track: Track?): Long {
        if (track == null || !settings.contentMode.remembersPosition) return 0L
        val pos = store.bookmark(track.uri)
        val duration = track.durationMs
        return if (pos > MIN_BOOKMARK_MS && (duration <= 0L || pos < duration - MIN_BOOKMARK_MS)) pos else 0L
    }

    private fun markFinished(track: Track) {
        if (!settings.contentMode.remembersPosition) return
        store.clearBookmark(track.uri)
        if (track.uri !in playedUris) {
            playedUris = playedUris + track.uri
            store.savePlayedUris(playedUris)
        }
    }

    override fun clearPlayedMarks() {
        playedUris = emptySet()
        store.savePlayedUris(playedUris)
        publish()
    }

    private fun fillDuration(uri: String, durationMs: Long) {
        val index = queue.tracks.indexOfFirst { it.uri == uri }
        if (index < 0) return
        val updated = queue.tracks.toMutableList()
        updated[index] = updated[index].copy(durationMs = durationMs)
        queue.restore(updated, queue.currentIndex, queue.shuffle, queue.playOrder())
        store.saveTracks(queue.tracks)
    }

    private fun updateSettings(newSettings: PlayerSettings) {
        settings = newSettings
        store.saveSettings(newSettings)
        publish()
    }

    private fun publish(loading: Boolean = loadJob?.isActive == true) {
        _state.value = PlayerState(
            tracks = queue.tracks,
            currentIndex = queue.currentIndex,
            isPlaying = wantPlaying,
            isLoading = loading,
            shuffle = queue.shuffle,
            settings = settings,
            format = if (loadedUri != null) engine.format() else null,
            signalPath = signalPath,
            bitPerfectAvailable = bitPerfectAvailable,
            playedUris = playedUris,
            sleepTimerEndsAt = sleepDeadline,
            importing = importing,
            libraryUpdate = libraryProgress,
            analysis = analysisProgress,
            rendererName = if (renderer != null) rendererName() else null,
        )
        if (::browseTree.isInitialized) publishSessionQueue()
        updateSessionState()
        updateRendererStatus()
        val track = queue.current
        PlayerWidget.update(this, track?.title, track?.artist?.ifBlank { track.folder }, wantPlaying, art?.takeIf { artUri == track?.uri })
    }

    private fun engineVolume(): Double = if (muted) 0.0 else settings.volume * duckFactor * sleepFactor

    private fun applyVolume() {
        engine.setVolume(engineVolume())
    }

    private fun applyEq(eq: EqSettings) = engine.setEq(eq)

    // ── Audio focus ──────────────────────────────────────────────────────────

    private fun requestFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
                .build()
                .also { focusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            _messages.tryEmit(getString(R.string.error_audio_focus))
            return false
        }
        // A fresh grant does not call the listener: drop any stale ducking.
        resetDuck()
        return true
    }

    private fun resetDuck() {
        if (duckFactor != 1.0) {
            duckFactor = 1.0
            applyVolume()
        }
    }

    private fun abandonFocus() {
        resetDuck()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    // ── Media session ────────────────────────────────────────────────────────

    private fun setupSession() {
        session = MediaSessionCompat(this, "HiFiPlayer").apply {
            setSessionActivity(contentIntent())
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stop()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onSkipToQueueItem(id: Long) = playIndex(id.toInt())
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = playMediaId(mediaId)
                override fun onPlayFromSearch(query: String?, extras: Bundle?) = playFromSearch(query)
            })
            isActive = true
        }
    }

    private fun updateSessionState() {
        if (!::session.isInitialized) return
        val playbackState = when {
            loadJob?.isActive == true -> PlaybackStateCompat.STATE_BUFFERING
            wantPlaying -> PlaybackStateCompat.STATE_PLAYING
            queue.current != null -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH,
                )
                .setState(
                    if (lastError != null && !wantPlaying) PlaybackStateCompat.STATE_ERROR else playbackState,
                    _position.value.positionMs,
                    if (wantPlaying) settings.speed else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .setActiveQueueItemId(queue.currentIndex.toLong())
                .apply {
                    lastError?.let { setErrorMessage(PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR, it) }
                }
                .build(),
        )
    }

    private fun updateSessionMetadata() {
        if (!::session.isInitialized) return
        val track = queue.current
        val artist = track?.artist?.ifBlank { null } ?: track?.folder.orEmpty()
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track?.title.orEmpty())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track?.album.orEmpty())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, track?.title.orEmpty())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, _position.value.durationMs.coerceAtLeast(track?.durationMs ?: 0L))
                .apply {
                    if (track != null && art != null && artUri == track.uri) {
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                        putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, CoverProvider.uri(this@PlaybackService, track.uri).toString())
                    }
                }
                .build(),
        )
        if (isForeground || wantPlaying) updateNotification()
        loadArt(track)
    }

    /** Loads the current track's cover for the lock screen, notification and car, then republishes. */
    private fun loadArt(track: Track?) {
        val uri = track?.uri
        if (uri == artUri) return   // loaded, loading, or known to have none
        if (uri == null || !CoverArt.isLocal(uri)) {
            art = null
            artUri = uri
            return
        }
        artJob?.cancel()
        artUri = uri
        art = null
        CoverProvider.allow(uri)
        artJob = scope.launch {
            val bitmap = runCatching { CoverArt.load(this@PlaybackService, uri, 512) }.getOrNull()
            if (artUri != uri) return@launch
            art = bitmap
            artJob = null
            if (bitmap != null) {
                updateSessionMetadata()
                publish()
            }
        }
    }

    // ── Notification / foreground ────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun buildNotification(): Notification {
        val track = queue.current
        val stopIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)
        val format = engine.format()
        val subtitle = listOfNotNull(
            (track?.artist?.ifBlank { null } ?: track?.folder)?.takeIf { it.isNotBlank() },
            format?.let { FormatText.short(it) },
        ).joinToString("  ·  ")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(track?.title ?: getString(R.string.app_name))
            .setContentText(subtitle)
            .setLargeIcon(art?.takeIf { artUri == track?.uri })
            .setContentIntent(contentIntent())
            .setDeleteIntent(stopIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(wantPlaying)
            .addAction(
                R.drawable.ic_skip_previous, getString(R.string.action_previous),
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS),
            )
            .addAction(
                if (wantPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (wantPlaying) R.string.action_pause else R.string.action_play),
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE),
            )
            .addAction(
                R.drawable.ic_skip_next, getString(R.string.action_next),
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT),
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopIntent),
            )
            .build()
    }

    private fun updateNotification() {
        if (!isForeground && !wantPlaying && queue.current == null) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied: playback continues without a visible notification.
        }
    }

    private fun enterForeground() {
        idleStopJob?.cancel()
        if (isForeground) {
            updateNotification()
            return
        }
        try {
            // The started state keeps the service alive after the UI unbinds.
            ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))
        } catch (_: Exception) {
            // Not allowed from the background (Android 12+); if the service is
            // already started (paused notification) promotion below still works.
        }
        promoteToForeground()
    }

    private fun promoteToForeground() {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
            isForeground = true
        } catch (_: Exception) {
            isForeground = false
        }
    }

    private fun leaveForeground(removeNotification: Boolean) {
        if (isForeground) {
            ServiceCompat.stopForeground(
                this,
                if (removeNotification) ServiceCompat.STOP_FOREGROUND_REMOVE else ServiceCompat.STOP_FOREGROUND_DETACH,
            )
            isForeground = false
        }
        if (removeNotification) {
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        } else {
            updateNotification()
        }
        // A paused player lingers for a while so the notification can resume it.
        idleStopJob?.cancel()
        idleStopJob = scope.launch {
            delay(IDLE_STOP_MS)
            if (!wantPlaying) {
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                stopSelf()
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1
        private const val PRELOAD_WINDOW_MS = 20_000L
        private const val RESTART_THRESHOLD_MS = 3_000L
        private const val MIN_BOOKMARK_MS = 2_000L
        private const val SLEEP_FADE_MS = 30_000L
        /** How long the indicator says "processed" after the engine last rounded a sample. */
        private const val PROCESSED_HOLD_MS = 1_500L
        private const val VOLUME_KEY_STEP = 0.05f
        private const val IDLE_STOP_MS = 10 * 60_000L
        private const val MAX_SKIPS = 5

        /** Hosts allowed to read the library (they can always control playback). */
        private val TRUSTED_BROWSERS = setOf(
            "com.google.android.projection.gearhead",       // Android Auto
            "com.google.android.carassistant",               // Assistant in the car
            "com.google.android.googlequicksearchbox",       // Google Assistant
            "com.android.car.media",                         // Android Automotive media centre
            "com.android.systemui",                          // media controls
            "com.android.bluetooth",                         // AVRCP browsing
        )
    }
}
