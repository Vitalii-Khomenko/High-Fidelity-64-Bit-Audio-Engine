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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.RectangleShape
import com.aiproject.musicplayer.db.AlbumRow
import com.aiproject.musicplayer.db.ArtistRow
import com.aiproject.musicplayer.library.LibraryIndex
import com.aiproject.musicplayer.ui.components.Cover
import com.aiproject.musicplayer.ui.components.Segmented
import com.aiproject.musicplayer.ui.components.pixelGlow
import kotlinx.coroutines.delay
import kotlin.random.Random
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
    data class Album(val album: AlbumRow, val back: LibraryPage) : LibraryPage
    data class Artist(val artist: ArtistRow) : LibraryPage
    data class IndexedFolder(val folder: LibraryIndex.Folder) : LibraryPage
}

enum class LibraryMode(val label: Int) {
    ALBUMS(R.string.albums), ARTISTS(R.string.artists), FOLDERS(R.string.folders), SOURCES(R.string.sources),
}

@Composable
fun LibraryScreen(state: PlayerState, commands: PlayerCommands, onPlayed: () -> Unit, initialMode: LibraryMode = LibraryMode.ALBUMS) {
    var page by remember { mutableStateOf<LibraryPage>(LibraryPage.Home) }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    BackHandler(enabled = page != LibraryPage.Home) {
        page = when (val p = page) {
            is LibraryPage.Folder -> if (p.stack.size > 1) p.copy(stack = p.stack.dropLast(1)) else LibraryPage.Home
            is LibraryPage.Network -> if (p.stack.isNotEmpty()) p.copy(stack = p.stack.dropLast(1)) else LibraryPage.Home
            is LibraryPage.Album -> p.back
            is LibraryPage.Artist -> LibraryPage.Home
            is LibraryPage.IndexedFolder -> LibraryPage.Home
            LibraryPage.Home -> LibraryPage.Home
        }
    }
    when (val p = page) {
        LibraryPage.Home -> Column(Modifier.fillMaxSize()) {
            Segmented(
                LibraryMode.entries, mode, label = { stringResource(it.label) }, onSelect = { mode = it },
                modifier = Modifier.padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 6.dp), tone = aw().violet,
            )
            state.libraryUpdate?.let { progress ->
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = aw().violet, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.library_updating, progress.folder, progress.tracks),
                        style = Aw.small, color = aw().muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when (mode) {
                LibraryMode.ALBUMS, LibraryMode.ARTISTS -> CollectionHome(
                    state, commands, mode, onPlayed,
                    onOpen = { page = it },
                    onShowSources = { mode = LibraryMode.SOURCES },
                )
                LibraryMode.FOLDERS -> FoldersHome(state, commands, onOpen = { page = it }, onShowSources = { mode = LibraryMode.SOURCES })
                LibraryMode.SOURCES -> SourcesHome(state, commands, onPlayed, onOpen = { page = it })
            }
        }
        is LibraryPage.Folder -> FolderBrowser(p, commands, onPlayed, onNavigate = { page = it })
        is LibraryPage.Network -> NetworkBrowser(p, commands, onPlayed, onNavigate = { page = it })
        is LibraryPage.Album -> AlbumPage(p, commands, onPlayed, onBack = { page = p.back })
        is LibraryPage.Artist -> ArtistPage(p, commands, onPlayed, onBack = { page = LibraryPage.Home }, onOpen = { page = it })
        is LibraryPage.IndexedFolder -> IndexedFolderPage(p.folder, commands, onPlayed, onBack = { page = LibraryPage.Home })
    }
}

@Composable
private fun aw() = Aw.colors

/** Albums and artists shown by previews and screenshots (there is no database there). */
val LocalLibraryPreview = androidx.compose.runtime.staticCompositionLocalOf<Pair<List<AlbumRow>, List<ArtistRow>>?> { null }

// ── Albums and artists ──────────────────────────────────────────────────────

@Composable
private fun rememberLibraryIndex(): LibraryIndex? {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    return remember { if (preview) null else LibraryIndex(context) }
}

@Composable
private fun CollectionHome(
    state: PlayerState,
    commands: PlayerCommands,
    mode: LibraryMode,
    onPlayed: () -> Unit,
    onOpen: (LibraryPage) -> Unit,
    onShowSources: () -> Unit,
) {
    val aw = Aw.colors
    val index = rememberLibraryIndex()
    val sample = LocalLibraryPreview.current
    val albums by remember(index) { index?.albums() ?: flowOf(sample?.first.orEmpty()) }.collectAsState(initial = sample?.first.orEmpty())
    val artists by remember(index) { index?.artists() ?: flowOf(sample?.second.orEmpty()) }.collectAsState(initial = sample?.second.orEmpty())
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Track>>(emptyList()) }
    LaunchedEffect(query, index) {
        found = if (query.trim().length >= 2 && index != null) {
            delay(200)
            runCatching { index.search(query) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }
    val q = query.trim().lowercase()
    val shownAlbums = if (q.isEmpty()) albums else albums.filter { q in it.albumTitle.lowercase() || q in it.albumArtistName.lowercase() }
    val shownArtists = if (q.isEmpty()) artists else artists.filter { q in it.name.lowercase() }

    if (albums.isEmpty() && state.libraryUpdate == null) {
        EmptyNote(
            stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_text),
            Modifier.padding(horizontal = 20.dp),
        ) {
            AwButton(stringResource(R.string.sources), onShowSources, tone = aw.violet)
            AwButton(stringResource(R.string.update_library), commands::updateLibrary)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SearchField(query, onChange = { query = it })
        }
        if (mode == LibraryMode.ALBUMS) {
            items(shownAlbums, key = { "al-${it.albumKey}" }) { album ->
                AlbumTile(album) { onOpen(LibraryPage.Album(album, LibraryPage.Home)) }
            }
        } else {
            items(shownArtists, key = { "ar-${it.artistKey}" }, span = { GridItemSpan(maxLineSpan) }) { artist ->
                ArtistRowItem(artist) { onOpen(LibraryPage.Artist(artist)) }
            }
        }
        if (found.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader("—", stringResource(R.string.tracks), aw.cyan, Modifier.padding(top = 10.dp))
            }
            items(found, key = { "t-${it.uri}" }, span = { GridItemSpan(maxLineSpan) }) { track ->
                LibraryTrackRow(track, showArtist = true, onPlay = {
                    commands.setQueue(found, found.indexOf(track), true)
                    onPlayed()
                }, onAdd = { commands.addTracks(listOf(track)) })
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val aw = Aw.colors
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        placeholder = { Text(stringResource(R.string.search_library), style = Aw.small, color = aw.muted) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = aw.muted, modifier = Modifier.size(18.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                SquareIconButton(Icons.Outlined.Close, stringResource(R.string.clear), { onChange("") }, size = 36.dp, iconSize = 16.dp, tint = aw.muted)
            }
        },
        singleLine = true,
        textStyle = Aw.body,
        shape = RectangleShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = aw.violet, unfocusedBorderColor = aw.line,
            focusedTextColor = aw.paper, unfocusedTextColor = aw.paper, cursorColor = aw.violet,
        ),
    )
}

@Composable
private fun AlbumTile(album: AlbumRow, onClick: () -> Unit) {
    val aw = Aw.colors
    Column(Modifier.clickable(onClick = onClick)) {
        Cover(album.coverUri, 160.dp, Modifier.fillMaxWidth().aspectRatio(1f), seed = album.albumKey)
        Spacer(Modifier.height(8.dp))
        Text(album.albumTitle, style = Aw.bodyStrong, color = aw.paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(artistLabel(album), album.year).joinToString("  ·  "),
            style = Aw.small, color = aw.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun artistLabel(album: AlbumRow): String = when {
    album.artists > 1 && album.albumArtistName.isBlank() -> stringResource(R.string.various_artists)
    album.albumArtistName.isBlank() -> stringResource(R.string.unknown_artist)
    else -> album.albumArtistName
}

@Composable
private fun ArtistRowItem(artist: ArtistRow, onClick: () -> Unit) {
    val aw = Aw.colors
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(artist.coverUri, 52.dp, Modifier.size(52.dp), seed = artist.artistKey)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name.ifBlank { stringResource(R.string.unknown_artist) }, style = Aw.body, color = aw.paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                context.resources.getQuantityString(R.plurals.album_count, artist.albums, artist.albums) + "  ·  " +
                    context.resources.getQuantityString(R.plurals.track_count, artist.tracks, artist.tracks),
                style = Aw.small, color = aw.muted,
            )
        }
    }
}

// ── Folders: every indexed folder with its own files, artists mixed ─────────

@Composable
private fun FoldersHome(state: PlayerState, commands: PlayerCommands, onOpen: (LibraryPage) -> Unit, onShowSources: () -> Unit) {
    val aw = Aw.colors
    val context = LocalContext.current
    val index = rememberLibraryIndex()
    val folders by remember(index) { index?.folders() ?: flowOf(emptyList()) }.collectAsState(initial = null)
    var query by rememberSaveable { mutableStateOf("") }
    val all = folders ?: return
    if (all.isEmpty() && state.libraryUpdate == null) {
        EmptyNote(
            stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_text),
            Modifier.padding(horizontal = 20.dp),
        ) {
            AwButton(stringResource(R.string.sources), onShowSources, tone = aw.violet)
            AwButton(stringResource(R.string.update_library), commands::updateLibrary)
        }
        return
    }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) all else all.filter { q in it.name.lowercase() || q in it.parent.lowercase() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item { SearchField(query, onChange = { query = it }) }
        item { Spacer(Modifier.height(10.dp)) }
        items(shown, key = { it.uri }) { folder ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(LibraryPage.IndexedFolder(folder)) }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(folder.coverUri, 52.dp, Modifier.size(52.dp), seed = folder.uri)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(folder.name, style = Aw.body, color = aw.paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (folder.parent.isNotEmpty()) {
                        Text(folder.parent, style = Aw.small, color = aw.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        listOfNotNull(
                            context.resources.getQuantityString(R.plurals.track_count, folder.tracks, folder.tracks),
                            TimeFormat.span(folder.durationMs).ifEmpty { null },
                        ).joinToString("  ·  "),
                        style = Aw.small, color = aw.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun IndexedFolderPage(folder: LibraryIndex.Folder, commands: PlayerCommands, onPlayed: () -> Unit, onBack: () -> Unit) {
    val aw = Aw.colors
    val context = LocalContext.current
    val index = rememberLibraryIndex()
    var tracks by remember(folder.uri) { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(folder.uri) { tracks = index?.folderTracks(folder.uri).orEmpty() }
    val list = tracks.orEmpty()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SquareIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), onBack)
                Spacer(Modifier.width(6.dp))
                Eyebrow(stringResource(R.string.folders), Modifier.weight(1f), color = aw.violet)
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Cover(folder.coverUri, 96.dp, Modifier.size(96.dp).pixelGlow(aw.violet, aw.glow, 8.dp), seed = folder.uri)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(folder.name, style = Aw.heading, color = aw.head, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (folder.parent.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(folder.parent, style = Aw.small, color = aw.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOfNotNull(
                            context.resources.getQuantityString(R.plurals.track_count, folder.tracks, folder.tracks),
                            TimeFormat.span(folder.durationMs).ifEmpty { null },
                        ).joinToString("  ·  "),
                        style = Aw.mono, color = aw.muted,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AwButton(stringResource(R.string.play_all), { play(commands, list, 0, false); onPlayed() }, Modifier.weight(1f), tone = aw.violet, enabled = list.isNotEmpty())
                AwButton(stringResource(R.string.shuffle), { play(commands, list, 0, true); onPlayed() }, Modifier.weight(1f), enabled = list.isNotEmpty())
                AwButton(stringResource(R.string.add), {
                    Toast.makeText(context, context.getString(R.string.added_tracks, commands.addTracks(list), folder.name), Toast.LENGTH_SHORT).show()
                }, Modifier.weight(1f), enabled = list.isNotEmpty())
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(aw.line))
        }
        if (tracks == null) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), color = aw.violet, strokeWidth = 1.5.dp) } }
        }
        itemsIndexed(list, key = { _, t -> t.uri }) { i, track ->
            // A folder mixes artists: every row names its own.
            LibraryTrackRow(track, showArtist = true, number = i + 1, onPlay = {
                play(commands, list, i, false)
                onPlayed()
            }, onAdd = {
                val added = commands.addTracks(listOf(track))
                Toast.makeText(context, if (added > 0) context.getString(R.string.added_one, track.title) else context.getString(R.string.already_queued), Toast.LENGTH_SHORT).show()
            })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Plays [tracks]; with [shuffle] the order is shuffled and the start is random. */
private fun play(commands: PlayerCommands, tracks: List<Track>, start: Int, shuffle: Boolean) {
    if (tracks.isEmpty()) return
    commands.setShuffle(shuffle)
    commands.setQueue(tracks, if (shuffle) Random.nextInt(tracks.size) else start, true)
}

@Composable
private fun AlbumPage(page: LibraryPage.Album, commands: PlayerCommands, onPlayed: () -> Unit, onBack: () -> Unit) {
    val aw = Aw.colors
    val context = LocalContext.current
    val index = rememberLibraryIndex()
    val album = page.album
    var tracks by remember(album.albumKey) { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(album.albumKey) { tracks = index?.albumTracks(album.albumKey).orEmpty() }
    val list = tracks.orEmpty()
    val various = list.map { it.artist }.distinct().size > 1
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SquareIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), onBack)
                Spacer(Modifier.width(6.dp))
                Eyebrow(stringResource(R.string.albums), Modifier.weight(1f), color = aw.violet)
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Cover(album.coverUri, 132.dp, Modifier.size(132.dp).pixelGlow(aw.violet, aw.glow, 8.dp), seed = album.albumKey)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(album.albumTitle, style = Aw.heading, color = aw.head, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(artistLabel(album), style = Aw.body, color = aw.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOfNotNull(
                            album.year,
                            context.resources.getQuantityString(R.plurals.track_count, album.tracks, album.tracks),
                            TimeFormat.span(album.durationMs).ifEmpty { null },
                        ).joinToString("  ·  "),
                        style = Aw.mono, color = aw.muted,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AwButton(stringResource(R.string.play_all), { play(commands, list, 0, false); onPlayed() }, Modifier.weight(1f), tone = aw.violet, enabled = list.isNotEmpty())
                AwButton(stringResource(R.string.shuffle), { play(commands, list, 0, true); onPlayed() }, Modifier.weight(1f), enabled = list.isNotEmpty())
                AwButton(stringResource(R.string.add), {
                    Toast.makeText(context, context.getString(R.string.added_tracks, commands.addTracks(list), album.albumTitle), Toast.LENGTH_SHORT).show()
                }, Modifier.weight(1f), enabled = list.isNotEmpty())
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(aw.line))
        }
        if (tracks == null) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), color = aw.violet, strokeWidth = 1.5.dp) } }
        }
        itemsIndexed(list, key = { _, t -> t.uri }) { i, track ->
            LibraryTrackRow(track, showArtist = various, number = track.trackNumber.takeIf { it > 0 } ?: (i + 1), onPlay = {
                play(commands, list, i, false)
                onPlayed()
            }, onAdd = {
                val added = commands.addTracks(listOf(track))
                Toast.makeText(context, if (added > 0) context.getString(R.string.added_one, track.title) else context.getString(R.string.already_queued), Toast.LENGTH_SHORT).show()
            })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ArtistPage(page: LibraryPage.Artist, commands: PlayerCommands, onPlayed: () -> Unit, onBack: () -> Unit, onOpen: (LibraryPage) -> Unit) {
    val aw = Aw.colors
    val context = LocalContext.current
    val index = rememberLibraryIndex()
    val artist = page.artist
    var albums by remember(artist.artistKey) { mutableStateOf<List<AlbumRow>>(emptyList()) }
    LaunchedEffect(artist.artistKey) { albums = index?.artistAlbums(artist.artistKey).orEmpty() }
    val scope = rememberCoroutineScope()
    fun playAll(shuffle: Boolean) = scope.launch {
        val tracks = index?.artistTracks(artist.artistKey).orEmpty()
        play(commands, tracks, 0, shuffle)
        if (tracks.isNotEmpty()) onPlayed()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SquareIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), onBack)
                    Spacer(Modifier.width(6.dp))
                    Eyebrow(stringResource(R.string.artists), Modifier.weight(1f), color = aw.violet)
                }
                Spacer(Modifier.height(8.dp))
                Text(artist.name.ifBlank { stringResource(R.string.unknown_artist) }, style = Aw.title, color = aw.head)
                Text(
                    context.resources.getQuantityString(R.plurals.album_count, artist.albums, artist.albums) + "  ·  " +
                        context.resources.getQuantityString(R.plurals.track_count, artist.tracks, artist.tracks),
                    style = Aw.mono, color = aw.muted,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AwButton(stringResource(R.string.play_all), { playAll(false) }, Modifier.weight(1f), tone = aw.violet)
                    AwButton(stringResource(R.string.shuffle), { playAll(true) }, Modifier.weight(1f))
                }
            }
        }
        items(albums, key = { it.albumKey }) { album ->
            AlbumTile(album) { onOpen(LibraryPage.Album(album, page)) }
        }
    }
}

@Composable
private fun LibraryTrackRow(track: Track, showArtist: Boolean, number: Int? = null, onPlay: () -> Unit, onAdd: () -> Unit) {
    val aw = Aw.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text("%02d".format(number), style = Aw.mono, color = aw.muted, modifier = Modifier.width(36.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, style = Aw.body, color = aw.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                track.artist.takeIf { showArtist && it.isNotBlank() },
                track.album.takeIf { showArtist && number == null && it.isNotBlank() },
                TimeFormat.clock(track.durationMs).takeIf { track.durationMs > 0 },
            ).joinToString("  ·  ")
            if (meta.isNotEmpty()) Text(meta, style = Aw.small, color = aw.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SquareIconButton(Icons.Outlined.Add, stringResource(R.string.add_to_queue), onAdd, size = 40.dp, iconSize = 18.dp)
    }
}

// ── Sources: folders, device, network, playlists ────────────────────────────

@Composable
private fun SourcesHome(state: PlayerState, commands: PlayerCommands, onPlayed: () -> Unit, onOpen: (LibraryPage) -> Unit) {
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
            commands.updateLibrary()
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
                    commands.forgetLibraryFolder(entry.uriString)
                    folders = LibraryFolders.load(context)
                    accessible = LibraryFolders.accessibleUris(context)
                }, size = 40.dp, iconSize = 16.dp, tint = aw.muted)
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AwButton(stringResource(R.string.add_folder), { pickFolder.launch(null) }, tone = aw.violet, icon = Icons.Outlined.Add)
                if (folders.isNotEmpty()) {
                    AwButton(stringResource(R.string.update_library), commands::updateLibrary, enabled = state.libraryUpdate == null)
                }
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
