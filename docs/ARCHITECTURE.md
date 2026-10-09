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
| `…library` | SAF folder scanning and browsing, MediaStore scan, DLNA discovery / protocol / download cache, sorting, supported formats |
| `…db` | Room database for saved playlists |
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
- **Loading** — DLNA URLs are downloaded to the cache first
  (`DlnaPlaybackCache`); everything else opens through the content resolver and
  the descriptor is handed to native code.
- **Audio focus** — `AudioFocusPolicy` (pure Kotlin): transient loss pauses and
  resumes on gain, permanent loss pauses for good, duck lowers the volume. A
  user action cancels any pending resume. Unplugging headphones pauses.
- **Foreground** — the service starts itself and goes to the foreground when
  playback starts; on pause it detaches the notification and stays for ten
  minutes so the notification can resume; on stop it removes the notification.
- **Sleep timer** — lives in the service (monotonic clock), fades the volume over
  the last 30 seconds and pauses.

## Persistence

| Where | What |
|---|---|
| `player_state` preferences | queue (JSON), index, shuffle order, resume point, settings, per-file bookmarks (`pos_uri_<uri>`), saved SAF folders |
| `audiobook_progress` preferences | finished files (Books mode) |
| `ui` preferences | theme, whether permissions were requested |
| Room `musicplayer_database` | saved playlists and their tracks (schema 4, migration 3→4) |

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
