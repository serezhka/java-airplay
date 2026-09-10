# Java AirPlay Server

[![GitHub release](https://img.shields.io/github/v/release/serezhka/java-airplay)](https://github.com/serezhka/java-airplay/releases)
[![build](https://github.com/serezhka/java-airplay/actions/workflows/build.yaml/badge.svg)](https://github.com/serezhka/java-airplay/actions/workflows/build.yaml)
![ViewCount](https://views.whatilearened.today/views/github/serezhka/java-airplay.svg)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](http://opensource.org/licenses/MIT)

This project unites the [java-airplay-lib](https://github.com/serezhka/java-airplay-lib), [java-airplay-server](https://github.com/serezhka/java-airplay-server)
and [java-airplay-server-examples](https://github.com/serezhka/java-airplay-server-examples) into one.
It makes development a lot easier when all parts of the code are put together.

😩 Due to lack of free time and other priorities, this project is not actively maintained. 😩

## Demo

* Raspberry pi 4 model B (1280 x 720 @ 24 fps)

[![RASPBERRY](https://img.youtube.com/vi/uRvgVkLWfSI/hqdefault.jpg)](https://youtu.be/uRvgVkLWfSI)

* Windows laptop (1920 x 1080 @ 30 fps)

[![RASPBERRY](https://img.youtube.com/vi/RT1hVWGJzos/hqdefault.jpg)](https://youtu.be/RT1hVWGJzos)

## How to Run

### From sources

```shell
git clone https://github.com/serezhka/java-airplay
cd ./java-airplay
./gradlew bootRun
```

### Pre-built app

Download the latest release

```shell
java -jar java-airplay-server-{version}.jar
```

## Configuration

Create `application.properties` file in working dir

### Available properties

```properties
# airplay
airplay.serverName=srzhka
airplay.width=1280
airplay.height=720
airplay.fps=24
# player (gstreamer, ffmpeg, vlc)
player.implementation=gstreamer
player.tray.enabled=true
# dump (optional sidecar, independent of the player)
dump.enabled=false
dump.directory=dumps
dump.protocol=true
dump.video=true
dump.audio=true
dump.playlist=true
dump.artwork=true
dump.videoFps=60
```

## Players

### Gstreamer

Supports both video and audio (alac + aac_eld) streams <br>
Gstreamer installation is required (see https://github.com/gstreamer-java/gst1-java-core)

AirPlay audio is routed by the sender OS. When an iPhone or iPad mirrors to this
receiver, iOS/iPadOS may move playback audio from the device speaker to the
AirPlay receiver. The receiver can play the audio it receives, but it cannot
force the sender device speaker to play at the same time. Dual playback requires
an additional sender-side or companion-device audio path outside the AirPlay
receiver protocol.

### FFmpeg

`ffplay` must be on PATH. Logs are written under `logs/` in the working directory.

| Mode | Stock distro ffmpeg | Notes |
|------|---------------------|-------|
| Screen mirroring video | yes | H.264 pipe to ffplay |
| Music (ALAC) | yes | native `alac` decoder |
| YouTube / HLS | yes | `ffplay <local playlist uri>` |
| Test-client audio (AAC-LC ADTS) | yes | `ffplay -f aac` |
| Mirroring audio (AAC-ELD) | usually no | needs `--enable-libfdk-aac` build |

`libfdk-aac` is still not in default Debian/Ubuntu ffmpeg packages because of
license/patent constraints. Distro builds use the native `aac` encoder/decoder
instead, which does not cover AirPlay's AAC-ELD mirroring audio. Music over
AirPlay is ALAC, not AAC-ELD, so Apple Music works without libfdk-aac.

For mirroring audio use the GStreamer player (`avdec_aac`), or install a custom
ffmpeg build with `--enable-nonfree --enable-libfdk-aac`. Keep player backends
separate — `FFmpegPlayer` does not fall back to GStreamer.

Logs (created in `./logs/` next to the process working directory):

- `airplay-app-<timestamp>-<pid>.log` — Spring / Netty application log
- `airplay-gst-<timestamp>-<pid>.log` — GStreamer (`-Dairplay.gst.debug.file=...`)
- `airplay-ffmpeg-<timestamp>-<pid>.log` — ffplay (`-Dairplay.ffmpeg.debug.file=...`)
- `airplay-vlc-<timestamp>-<pid>.log` — VLC (`-Dairplay.vlc.debug.file=...`)

Override directory with `-Dairplay.logs.directory=...`.

### VLC

Playback stops after few seconds <br>
VLC installation is required

### dump

Set `dump.enabled=true` to record the session beside gstreamer, ffmpeg, or vlc. Dumps go under `dumps/<timestamp>_<sessionId>/`:

- `protocol/` — RTSP/HTTP request and response captures, including HLS `GET /playlist`
- `media/video-NNN.h264` plus `media/video-NNN.mp4` when `ffmpeg` is on PATH — decrypted video remuxed at `dump.videoFps` (default 60)
- `media/audio-NNN.caf` — ALAC in a CAF container with a magic cookie and packet table; AAC is dumped as `.aac`
- `extras/` — HLS playlist URI, master/media `m3u8` from YouTube FCUP, artwork, and DMAP metadata when the sender provides them

Play `*.mp4` / `*.caf` in ffplay or VLC. Raw `.h264` has no timestamps, so players often guess 25 fps and look sluggish.

YouTube (and other HLS senders) use `POST /play` plus a reverse HTTP event channel. The receiver answers `/play` first, then fetches playlists through FCUP.

Screen mirroring typically has no cover art. Album artwork usually arrives as RTSP `SET_PARAMETER` with `Content-Type: image/jpeg` or `image/png`.

## Playback smoke tests

Playback tests use a synthetic H264 test pattern, so an AirPlay sender is not required.
They open the real player window for about two seconds and are deliberately not included
in `build`, `check`, or the regular `test` task.

```shell
# All supported playback implementations
./gradlew playbackTest

# One implementation
./gradlew ffmpegPlaybackTest
./gradlew gstreamerPlaybackTest
./gradlew dumpPlaybackTest
```