# Playback lifecycle integration tests

Shared by `app/gstreamer`, `app/ffmpeg`, and `app/mse` (`*PlaybackTest` + `PlaybackLifecycleHarness`).
Each case drives the **production** `Playback` implementation (same code as the release fat jar).

Wall-clock delays below are intentional — they mimic a user who watches for a bit,
then changes mode — not “as fast as the JVM can push buffers”.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Phone as Phone / sender
    participant App as Playback (GST or FFmpeg)

    Note over User,App: Case A — Mirror smoke (~2s video)
    User->>Phone: Start screen mirroring
    Phone->>App: onVideoFormat + H264 AUs (~60 frames @ 33ms)
    App-->>User: Fullscreen / sink playing
    Phone->>App: onVideoSrcDisconnect
    App-->>User: Pipeline / ffplay stopped

    Note over User,App: Case B — Reconnect (drop + mirror again)
    Phone->>App: onVideoFormat + ~1s video
    Phone->>App: disconnect
    Phone->>App: onVideoFormat + ~1s video again
    App-->>User: Second session still alive

    Note over User,App: Case C — Mirror + audio + volume
    Phone->>App: onVideoFormat (picture)
    Phone->>App: onAudioFormat(AAC-LC) + AUs (~0.8s)
    Phone->>App: onVolume(0.35→0→1)
    Note right of App: Video thread and audio feed overlap
    Phone->>App: disconnect audio + video

    Note over User,App: Case D — Hybrid YouTube-style round-trip
    Phone->>App: Mirror ~0.7s then disconnect
    Phone->>App: onPlaylist(local mediadata.m3u8)
    App-->>Phone: onPresented (first picture)
    Note right of App: Watch until position ≥ 0.3s
    Phone->>App: onPause (~0.7s hold)
    Note right of App: Position must stay put
    Phone->>App: onResume (position advances)
    Phone->>App: onSeek(+~1.5s)
    Phone->>App: onPlaylistRemoved
    Phone->>App: Mirror again ~0.7s
    App-->>User: Back on mirror sink

    Note over User,App: Case E — Audio-only (Music)
    Phone->>App: onAudioFormat(AAC-LC) + AUs + volume
    Phone->>App: onAudioSrcDisconnect

    Note over User,App: Case F — Audio reconnect while mirroring
    Phone->>App: Mirror + audio
    Phone->>App: onAudioSrcDisconnect (picture stays)
    Phone->>App: onAudioFormat + AUs again

    Note over User,App: Case G/H — Volume clamp + idempotent disconnect
    Phone->>App: onVolume(-0.5→1.5→0.25)
    Phone->>App: disconnect ×2 (no throw)

    Note over User,App: Case I — HLS volume + mid-play stop → mirror
    Phone->>App: onPlaylist + volume while clock moves
    Phone->>App: onPlaylistRemoved (idle info)
    Phone->>App: Mirror again

    Note over User,App: Case J — Mirror tears active HLS
    Phone->>App: onPlaylist (playing)
    Phone->>App: onVideoFormat (HLS must stop)
```

## Timing cheat-sheet

| Step | Approx. real time | How the test does it |
|------|-------------------|----------------------|
| Mirror watch | ~2 s | 60× `onVideo` @ 33 ms |
| Short mirror burst | ~0.7–1.3 s | 20–40 frames @ 33 ms |
| AAC-LC burst | ~0.8 s | ADTS chunks @ 20 ms |
| Pause hold | ~0.7 s | `Thread.sleep` after `onPause` |
| HLS latch | up to ~20–25 s | wait for `position ≥ 0.3` |

CI-only knobs (not release `application.properties`): `airplay.*.hls.headless`,
`airplay.gst.appsink`, xvfb / Pulse null sink on the runner.
