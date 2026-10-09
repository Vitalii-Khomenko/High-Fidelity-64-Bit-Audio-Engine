# Architecture

```
┌──────────────────────────── app (Kotlin) ─────────────────────────────┐
│ MainActivity ── binds ──► PlaybackService ◄── MediaSession / buttons  │
│   └ Compose UI (ui/)         │  owns the queue, focus, notification   │
│      observes StateFlow      │  persists state (PlayerStore)          │
│      sends PlayerCommands    ▼                                        │
│                         AudioEngine (JNI wrapper)                     │
└──────────────────────────────┬────────────────────────────────────────┘
                               │ JNI (libaudioengine.so)
┌──────────────────────────────▼────────────────────────────────────────┐
│ AudioPlayer ── decode thread ──► RingBuffer ──► OboeOutput ──► device │
│ decoders · EQ · ReplayGain · time-stretch · DSD decimator · spectrum  │
└───────────────────────────────────────────────────────────────────────┘
```

The engine is described in [AUDIO_ENGINE.md](AUDIO_ENGINE.md). This page covers
the Android app.

## Packages

| Package | Contents |
|---|---|
| `com.aiproject.musicplayer` | `MainActivity`, `AudioEngine` (JNI names depend on this location) |
| `…playback` | `PlaybackService`, `PlaybackQueue`, `PlayerStore`, `AudioFocusPolicy`, models (`Track`, modes), `FormatText`, `OutputDevice` |
| `…library` | SAF folder scanning and browsing (CUE sheets expanded into tracks, `CueSheet.kt`), MediaStore scan, DLNA discovery / protocol / download cache, sorting, supported formats |
| `…playback` (`PlayableUri`) | A CUE track's URI is the audio file's URI plus `#hifi-cue=<startUs>-<endUs>`; `AudioEngine` splits it and passes the range to the engine |
| `…db` | Room database: saved playlists and the library index (`LibraryEntities.kt`) |
| `…ui` | Compose screens (Player, Library, Sound, Settings), theme and components |

## Playback service

`PlaybackService` is the single owner of playback. The activity only binds to
it, renders `state` / `position` and calls `PlayerCommands`.

- **Queue** — `PlaybackQueue` (pure Kotlin) holds the tracks, the current index
  and the play order. Shuffle is a permutation walked by a cursor, so *next*,
  *previous* and the gapless pre-load always agree. Repeat-one repeats only on
  automatic advance; a user *next* still moves on.
- **Threads** — queue and state change on the main thread; engine transport
  calls run in order on one `engine-control` thread. Queries go to the engine
  directly (they never block).
- **Monitor** — every 200 ms while playing (750 ms otherwise) the service reads
  the engine state and position, handles `Ended` (advance, repeat, end of
  queue), gapless switches and errors (skips up to five broken files during
  continuous playback), pre-loads the next track 20 s before the end, and saves
  the resume point every 5 s.
- **Library import** — folder and device scans run in the service scope, so
  they finish even if the user leaves the screen; results arrive as messages.
- **Binding** — `MainActivity` binds in `onCreate` and unbinds in `onDestroy`.
  System pickers stop the activity; unbinding there used to drop the UI that
  was waiting for the picker result.
- **Loading** — DLNA URLs are downloaded to the cache first
  (`DlnaPlaybackCache`); everything else opens through the content resolver and
  the descriptor is handed to native code.
- **Pre-load cancellation** — cancelling the coroutine cannot stop a JNI
  open in progress, so invalidation always calls `clearNext()` (the native
  generation discards a late result) and a finished pre-load is accepted only
  if its URI is still the next track.
- **Foreground before focus** — the service promotes itself before requesting
  audio focus; Android 15+ grants focus only to foreground apps and services.
- **Audio focus** — `AudioFocusPolicy` (pure Kotlin): transient loss pauses and
  resumes on gain, permanent loss pauses for good, duck lowers the volume. A
  user action cancels any pending resume. Unplugging headphones pauses.
- **Foreground** — the service starts itself and goes to the foreground when
  playback starts; on pause it detaches the notification and stays for ten
  minutes so the notification can resume; on stop it removes the notification.
- **Sleep timer** — lives in the service (monotonic clock), fades the volume over
  the last 30 seconds and pauses.

## Android Auto

`PlaybackService` extends `MediaBrowserServiceCompat`. Android Auto binds with
the `MediaBrowserService` action and receives the browser binder; the app's own
activity binds without an action and receives `LocalBinder`.

| Node | Children |
|---|---|
| root | Queue, Playlists, Folders (browsable, list style) |
| Queue | tracks of the queue (window of 300 around the current one) |
| Playlists → playlist | saved playlists → their tracks |
| Folders → folder | saved SAF folders → subfolders and tracks of that level |

Media IDs (`playback/MediaIds.kt`) carry everything needed to play an item
(queue index + URI, playlist id + index, tree URI + document id + index), Base64
encoded. Picking a playlist or folder track replaces the queue with that list
and starts at the item. Voice search matches queue titles, then playlists, then
folders. `onGetRoot()` only gives the library to trusted hosts; everyone else
gets an empty root but can still use the media session.

Testing without a car: install the Desktop Head Unit from the SDK manager
(`extras;google;auto`), enable developer mode and "Unknown sources" in Android
Auto, then follow docs/TESTING.md.

## Library index and covers

`LibraryIndex` walks every saved folder (`SafTreeScanner.walk`), reads the
tags of each file natively (`NativeTags`: title, artist, album, album artist,
numbers, year, genre, embedded-cover flag) and its length from the headers
(`DurationProbe.h`, no decoding), and stores one row per file or CUE track in
`library_tracks`. Files whose size and modification time did not change are
not read again; rows of deleted files and removed folders are dropped. It runs
in the service scope: on the first start with folders but an empty index,
after a folder is added, and from *Update library*.

Albums group by album artist + album across folders, or by folder + album
when there is no album artist (compilations stay together). Search uses a
lower-cased text column because SQLite `LIKE` folds only ASCII case.

`CoverArt` returns the embedded picture of a file, else the folder picture
(`cover`, `folder`, `front`, `album`, `albumart` jpg/png/webp, or the only
picture in the folder), scaled to 600 px, cached once per picture under
`cacheDir/covers` (150 MB budget, least recently used dropped) and in memory
per size. `CoverProvider` serves them to Android Auto; it only answers for
library tracks and the track being played.

`LoudnessAnalyzer` measures files without ReplayGain tags (EBU R128, native)
on a single low-priority thread: the current and the next track while
playing, or the whole library on request. Results live in the `loudness`
table; a file found to carry tags is marked so it is never decoded for this.
Before a load or pre-load the service looks up the measured track and album
values and passes them to the engine, which uses them only when the file has
no tags.

`TrackProbe` completes queued tracks that lack tags or a length (from the
index, else from the file) in the background; in *Books* mode titles keep the
file names.

## Persistence

| Where | What |
|---|---|
| `player_state` preferences | queue (JSON), index, shuffle order, resume point, settings (incl. EQ mode and profile, crossfeed, limiter, auto-measure), per-file bookmarks (`pos_uri_<uri>`), saved SAF folders |
| `audiobook_progress` preferences | finished files (Books mode) |
| `ui` preferences | theme, whether permissions were requested |
| Room `musicplayer_database` | saved playlists and their tracks, the library index, loudness measurements (schema 7; migrations 3→4, 4→5 album fields, 5→6 library, 6→7 loudness) |
| `cacheDir/covers` | scaled cover pictures (can be cleared by the system) |

Keys are compatible with 0.8.x installs, so an update keeps the queue, folders,
bookmarks and EQ.

## UI

Four tabs — Player, Library, Sound, Settings — with a mini player on the
non-player tabs. The look follows airwitech.com: tokens in
`ui/theme/Theme.kt` (ink, paper, violet / amber / cyan, hairlines, glow
strength per theme), Sora for display text and labels, Source Sans 3 for body
text (Latin + Cyrillic subset), square shapes, pixel glyph icons
(`ui/components/PixelIcons.kt`) and section headers with a glowing tone bar.
`AppContent` renders the whole UI from plain state, which is how the
screenshots in `docs/screenshots` were produced.
