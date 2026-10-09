package com.aiproject.musicplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aiproject.musicplayer.R
import com.aiproject.musicplayer.db.MusicDatabase
import com.aiproject.musicplayer.db.PlaylistEntity
import com.aiproject.musicplayer.db.PlaylistStore
import com.aiproject.musicplayer.library.BrowseEntry
import com.aiproject.musicplayer.library.BrowseLocation
import com.aiproject.musicplayer.library.DlnaContainer
import com.aiproject.musicplayer.library.DlnaDiscovery
import com.aiproject.musicplayer.library.DlnaPage
import com.aiproject.musicplayer.library.DlnaServer
import com.aiproject.musicplayer.library.LibraryFolderEntry
import com.aiproject.musicplayer.library.LibraryFolders
import com.aiproject.musicplayer.library.MediaStoreScanner
import com.aiproject.musicplayer.library.PlaylistOrdering
import com.aiproject.musicplayer.library.SafTreeScanner
import com.aiproject.musicplayer.playback.PlayerCommands
import com.aiproject.musicplayer.playback.PlayerState
import com.aiproject.musicplayer.playback.TimeFormat
import com.aiproject.musicplayer.playback.Track
import com.aiproject.musicplayer.ui.components.AwButton
import com.aiproject.musicplayer.ui.components.EmptyNote
import com.aiproject.musicplayer.ui.components.Eyebrow
import com.aiproject.musicplayer.ui.components.SectionHeader
import com.aiproject.musicplayer.ui.components.SquareIconButton
import com.aiproject.musicplayer.ui.theme.Aw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface LibraryPage {
    data object Home : LibraryPage
    data class Folder(val root: LibraryFolderEntry, val stack: List<BrowseLocation>) : LibraryPage
    data class Network(val server: DlnaServer, val stack: List<DlnaContainer>) : LibraryPage
}

@Composable
fun LibraryScreen(state: PlayerState, commands: PlayerCommands, onPlayed: () -> Unit) {
    var page by remember { mutableStateOf<LibraryPage>(LibraryPage.Home) }
    BackHandler(enabled = page != LibraryPage.Home) {
        page = when (val p = page) {
            is LibraryPage.Folder -> if (p.stack.size > 1) p.copy(stack = p.stack.dropLast(1)) else LibraryPage.Home
            is LibraryPage.Network -> if (p.stack.isNotEmpty()) p.copy(stack = p.stack.dropLast(1)) else LibraryPage.Home
            LibraryPage.Home -> LibraryPage.Home
        }
    }
    when (val p = page) {
        LibraryPage.Home -> LibraryHome(state, commands, onPlayed, onOpen = { page = it })
        is LibraryPage.Folder -> FolderBrowser(p, commands, onPlayed, onNavigate = { page = it })
        is LibraryPage.Network -> NetworkBrowser(p, commands, onPlayed, onNavigate = { page = it })
    }
}

// ── Home ────────────────────────────────────────────────────────────────────

@Composable
private fun LibraryHome(state: PlayerState, commands: PlayerCommands, onPlayed: () -> Unit, onOpen: (LibraryPage) -> Unit) {
    val aw = Aw.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf(LibraryFolders.load(context)) }
    var accessible by remember { mutableStateOf(LibraryFolders.accessibleUris(context)) }
    var servers by remember { mutableStateOf<List<DlnaServer>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val preview = LocalInspectionMode.current
    val playlistStore = remember { PlaylistStore(MusicDatabase.getDatabase(context)) }
    val playlists by remember { if (preview) flowOf(emptyList()) else playlistStore.playlists() }.collectAsState(initial = emptyList())
    var renaming by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deleting by remember { mutableStateOf<PlaylistEntity?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    // Database work reports failures (full disk, corrupt DB) instead of crashing.
    fun playlistOp(block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            toast(context.getString(R.string.playlist_error))
        }
    }

    // Scanning runs in the service, so leaving this screen does not lose it.
    fun addFromFolder(entry: LibraryFolderEntry, play: Boolean) {
        commands.importFolder(entry.uriString, null, entry.label, play)
        if (play) onPlayed()
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val added = LibraryFolders.add(context, uri)
            folders = LibraryFolders.load(context)
            accessible = LibraryFolders.accessibleUris(context)
            if (!added.persisted) toast(context.getString(R.string.folder_access_temporary, added.entry.label))
            addFromFolder(added.entry, play = state.tracks.isEmpty())
        }
    }

    fun scanDevice() = commands.importDeviceLibrary()

    val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val requestMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanDevice() else toast(context.getString(R.string.permission_denied_media))
    }

    LazyColumn(Modifier.fillMaxSize()) {
        // 01 Folders
        item {
            SectionHeader("01", stringResource(R.string.folders), aw.violet, Modifier.padding(horizontal = 20.dp).padding(top = 12.dp))
            Text(stringResource(R.string.folders_hint), style = Aw.small, color = aw.muted, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(10.dp))
        }
        items(folders, key = { it.uriString }) { entry ->
            val ok = entry.uriString in accessible
            LibraryRow(
                icon = Icons.Outlined.Folder,
                title = entry.label,
                subtitle = if (ok) null else stringResource(R.string.access_lost),
                tone = aw.violet,
                onClick = { if (ok) onOpen(LibraryPage.Folder(entry, listOf(SafTreeScanner.rootLocation(Uri.parse(entry.uriString), entry.label)))) else pickFolder.launch(null) },
            ) {
                if (ok) SquareIconButton(Icons.Outlined.Add, stringResource(R.string.add_to_queue), { addFromFolder(entry, play = false) }, size = 40.dp, iconSize = 18.dp)
                SquareIconButton(Icons.Outlined.Close, stringResource(R.string.remove), {
                    LibraryFolders.remove(context, entry)
                    folders = LibraryFolders.load(context)
                    accessible = LibraryFolders.accessibleUris(context)
                }, size = 40.dp, iconSize = 16.dp, tint = aw.muted)
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AwButton(stringResource(R.string.add_folder), { pickFolder.launch(null) }, tone = aw.violet, icon = Icons.Outlined.Add)
            }
            state.importing?.let { label ->
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = aw.violet, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.scanning, label), style = Aw.small, color = aw.muted)
                }
            }
        }

        // 02 Device
        item {
            SectionHeader("02", stringResource(R.string.device), aw.amber, Modifier.padding(horizontal = 20.dp).padding(top = 22.dp))
            Text(stringResource(R.string.device_hint), style = Aw.small, color = aw.muted, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                AwButton(stringResource(R.string.scan_device), {
                    if (ContextCompat.checkSelfPermission(context, mediaPermission) == PackageManager.PERMISSION_GRANTED) scanDevice()
                    else requestMedia.launch(mediaPermission)
                }, tone = aw.amber)
            }
        }

        // 03 Network
        item {
            SectionHeader("03", stringResource(R.string.network), aw.cyan, Modifier.padding(horizontal = 20.dp).padding(top = 22.dp))
            Text(stringResource(R.string.network_hint), style = Aw.small, color = aw.muted, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AwButton(stringResource(if (servers == null) R.string.find_servers else R.string.rescan), {
                    scanning = true
                    scope.launch {
                        servers = runCatching { DlnaDiscovery.discoverServers(context) }.getOrDefault(emptyList())
                        scanning = false
                    }
                }, tone = aw.cyan, enabled = !scanning)
                if (scanning) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(14.dp), color = aw.cyan, strokeWidth = 1.5.dp)
                }
            }
            if (servers?.isEmpty() == true && !scanning) {
                Text(stringResource(R.string.no_servers), style = Aw.small, color = aw.muted, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        items(servers.orEmpty(), key = { it.controlUrl }) { server ->
            LibraryRow(Icons.Outlined.Lan, server.friendlyName, Uri.parse(server.location).host, aw.cyan, onClick = {
                onOpen(LibraryPage.Network(server, emptyList()))
            })
        }

        // 04 Playlists
        item {
            SectionHeader("04", stringResource(R.string.playlists), aw.paper, Modifier.padding(horizontal = 20.dp).padding(top = 22.dp))
            if (playlists.isEmpty()) {
                Text(stringResource(R.string.no_playlists), style = Aw.small, color = aw.muted, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        items(playlists, key = { "pl-${it.id}" }) { playlist ->
            LibraryRow(Icons.AutoMirrored.Outlined.QueueMusic, playlist.name, null, aw.paper, onClick = {
                playlistOp {
                    val tracks = playlistStore.tracks(playlist.id)
                    commands.setQueue(tracks, -1, false)
                    commands.setShuffle(playlist.shuffleEnabled)
                    toast(context.getString(R.string.loaded_playlist, playlist.name, tracks.size))
                    onPlayed()
                }
            }) {
                SquareIconButton(Icons.Outlined.Edit, stringResource(R.string.rename), { renaming = playlist }, size = 40.dp, iconSize = 16.dp, tint = aw.muted)
                SquareIconButton(Icons.Outlined.Close, stringResource(R.string.delete), { deleting = playlist }, size = 40.dp, iconSize = 16.dp, tint = aw.muted)
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                AwButton(stringResource(R.string.save_current_queue), { saving = true }, enabled = state.tracks.isNotEmpty())
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    renaming?.let { playlist ->
        TextInputDialog(stringResource(R.string.rename_playlist), stringResource(R.string.playlist_name), stringResource(R.string.rename), playlist.name,
            onDismiss = { renaming = null },
            onConfirm = { name -> renaming = null; playlistOp { playlistStore.rename(playlist.id, name) } })
    }
    deleting?.let { playlist ->
        ConfirmDialog(stringResource(R.string.delete_playlist), playlist.name, stringResource(R.string.delete),
            onDismiss = { deleting = null },
            onConfirm = { deleting = null; playlistOp { playlistStore.delete(playlist) } })
    }
    if (saving) {
        TextInputDialog(stringResource(R.string.save_playlist), stringResource(R.string.playlist_name), stringResource(R.string.save),
            onDismiss = { saving = false },
            onConfirm = { name ->
                saving = false
                playlistOp {
                    playlistStore.save(name, state.tracks, state.shuffle)
                    toast(context.getString(R.string.playlist_saved, name))
                }
            })
    }
}

// ── Folder browser ──────────────────────────────────────────────────────────

@Composable
private fun FolderBrowser(page: LibraryPage.Folder, commands: PlayerCommands, onPlayed: () -> Unit, onNavigate: (LibraryPage) -> Unit) {
    val aw = Aw.colors
    val context = LocalContext.current
    val location = page.stack.last()
    val treeUri = remember(page.root) { Uri.parse(page.root.uriString) }
    var entries by remember(location) { mutableStateOf<List<BrowseEntry>?>(null) }
    var error by remember(location) { mutableStateOf<String?>(null) }
    LaunchedEffect(location) {
        try {
            entries = withContext(Dispatchers.IO) { SafTreeScanner.listFolder(context.contentResolver, treeUri, location.documentId, location.label) }
        } catch (e: Exception) {
            error = e.message ?: context.getString(R.string.cannot_open_folder)
            entries = emptyList()
        }
    }
    val tracks = entries.orEmpty().mapNotNull { it.track }

    BrowserScaffold(
        path = page.stack.joinToString("  /  ") { it.label },
        tone = aw.violet,
        onBack = { onNavigate(if (page.stack.size > 1) page.copy(stack = page.stack.dropLast(1)) else LibraryPage.Home) },
        loading = entries == null,
        error = error,
        actions = {
            AwButton(stringResource(R.string.play_folder), {
                commands.importFolder(page.root.uriString, location.documentId, location.label, play = true)
                onPlayed()
            }, Modifier.weight(1f), tone = aw.violet)
            AwButton(stringResource(R.string.add_folder_to_queue), {
                commands.importFolder(page.root.uriString, location.documentId, location.label, play = false)
            }, Modifier.weight(1f))
        },
    ) {
        items(entries.orEmpty(), key = { it.documentId }) { entry ->
            if (entry.isDirectory) {
                LibraryRow(Icons.Outlined.Folder, entry.name, null, aw.violet, onClick = {
                    onNavigate(page.copy(stack = page.stack + BrowseLocation(entry.documentId, entry.name)))
                })
            } else {
                val track = entry.track ?: return@items
                TrackRow(track, onPlay = {
                    commands.setQueue(tracks, tracks.indexOf(track), true)
                    onPlayed()
                }, onAdd = {
                    val added = commands.addTracks(listOf(track))
                    Toast.makeText(context, if (added > 0) context.getString(R.string.added_one, track.title) else context.getString(R.string.already_queued), Toast.LENGTH_SHORT).show()
                })
            }
        }
    }
}

// ── DLNA browser ────────────────────────────────────────────────────────────

@Composable
private fun NetworkBrowser(page: LibraryPage.Network, commands: PlayerCommands, onPlayed: () -> Unit, onNavigate: (LibraryPage) -> Unit) {
    val aw = Aw.colors
    val context = LocalContext.current
    val containerId = page.stack.lastOrNull()?.id ?: "0"
    var content by remember(containerId) { mutableStateOf<DlnaPage?>(null) }
    var error by remember(containerId) { mutableStateOf<String?>(null) }
    LaunchedEffect(containerId) {
        try {
            content = DlnaDiscovery.browse(page.server, containerId)
        } catch (e: Exception) {
            error = e.message ?: context.getString(R.string.cannot_open_folder)
            content = DlnaPage(emptyList(), emptyList(), 0, 0)
        }
    }
    val folder = page.stack.lastOrNull()?.title ?: page.server.friendlyName
    val tracks = content?.tracks.orEmpty().map { Track(it.url, it.title, folder, it.durationMs) }

    BrowserScaffold(
        path = (listOf(page.server.friendlyName) + page.stack.map { it.title }).joinToString("  /  "),
        tone = aw.cyan,
        onBack = { onNavigate(if (page.stack.isNotEmpty()) page.copy(stack = page.stack.dropLast(1)) else LibraryPage.Home) },
        loading = content == null,
        error = error,
        actions = {
            AwButton(stringResource(R.string.play_all), { if (tracks.isNotEmpty()) { commands.setQueue(tracks, 0, true); onPlayed() } },
                Modifier.weight(1f), tone = aw.cyan, enabled = tracks.isNotEmpty())
            AwButton(stringResource(R.string.add_all), {
                Toast.makeText(context, context.getString(R.string.added_tracks, commands.addTracks(tracks), folder), Toast.LENGTH_SHORT).show()
            }, Modifier.weight(1f), enabled = tracks.isNotEmpty())
        },
    ) {
        items(content?.containers.orEmpty(), key = { "c-${it.id}" }) { container ->
            LibraryRow(Icons.Outlined.Folder, container.title, container.childCount.takeIf { it >= 0 }?.toString(), aw.cyan, onClick = {
                onNavigate(page.copy(stack = page.stack + container))
            })
        }
        itemsIndexed(tracks, key = { i, t -> "t-$i-${t.uri}" }) { index, track ->
            TrackRow(track, onPlay = { commands.setQueue(tracks, index, true); onPlayed() }, onAdd = {
                val added = commands.addTracks(listOf(track))
                Toast.makeText(context, if (added > 0) context.getString(R.string.added_one, track.title) else context.getString(R.string.already_queued), Toast.LENGTH_SHORT).show()
            })
        }
    }
}

// ── Shared pieces ───────────────────────────────────────────────────────────

@Composable
private fun BrowserScaffold(
    path: String,
    tone: Color,
    onBack: () -> Unit,
    loading: Boolean,
    error: String?,
    actions: @Composable RowScope.() -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val aw = Aw.colors
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SquareIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), onBack)
            Spacer(Modifier.width(6.dp))
            Eyebrow(path, Modifier.weight(1f), color = tone)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        Box(Modifier.fillMaxWidth().height(1.dp).background(aw.line))
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), color = tone, strokeWidth = 1.5.dp)
            }
            error != null -> EmptyNote(stringResource(R.string.cannot_open_folder), error, Modifier.padding(horizontal = 20.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), content = content)
        }
    }
}

@Composable
private fun LibraryRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    tone: Color,
    onClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val aw = Aw.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tone, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Aw.body, color = aw.paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = Aw.small, color = aw.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun TrackRow(track: Track, onPlay: () -> Unit, onAdd: () -> Unit) {
    val aw = Aw.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 52.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(track.title, style = Aw.body, color = aw.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (track.durationMs > 0) Text(TimeFormat.clock(track.durationMs), style = Aw.mono, color = aw.muted)
        }
        SquareIconButton(Icons.Outlined.Add, stringResource(R.string.add_to_queue), onAdd, size = 40.dp, iconSize = 18.dp)
    }
}
