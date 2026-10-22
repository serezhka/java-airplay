<!-- markdownlint-disable-next-line -->
<div align="center">

  <!-- markdownlint-disable-next-line -->
  # Java AirPlay

  Open-source AirPlay server in Java — receive audio, screen mirroring, and HLS video,
  and cast a local stream to other AirPlay devices.

  [![CI][badge-ci]][ci]&nbsp;
  [![GitHub release][badge-release]][releases]&nbsp;
  [![License: MIT][badge-license]][license]&nbsp;
  [![Java][badge-java]][openjdk]&nbsp;
  ![ViewCount][badge-views]

</div>

## Demo

- [Raspberry Pi 4 Model B (1280×720 @ 24 fps)](https://youtu.be/uRvgVkLWfSI)
- [Windows laptop (1920×1080 @ 30 fps)](https://youtu.be/RT1hVWGJzos)

## Features

- AirPlay audio and screen mirroring (FairPlay decrypt)
- HLS video playback
- Volume control
- Two native playback backends: GStreamer and FFmpeg (`ffplay`)
- Optional session dump (protocol + decrypted media) for debugging
- System tray with cast picker (discover LAN receivers and send a stream)

## Experimental features

- Cast synthetic test A/V (`client.video.src=videotest`, `client.audio.src=audiotest`)
  to another AirPlay receiver from the tray menu — useful for end-to-end send-path
  checks without a camera or screen capture

## Quick start

### From source

```shell
git clone https://github.com/serezhka/java-airplay.git
cd java-airplay
./gradlew :app:gstreamer:bootRun
```

FFmpeg backend: `./gradlew :app:ffmpeg:bootRun`.  
`./gradlew bootJar` builds both fat jars.

### Pre-built jar

Download the [latest release](https://github.com/serezhka/java-airplay/releases/latest), then run one backend:

```shell
java -jar java-airplay-gstreamer-{version}.jar
java -jar java-airplay-ffmpeg-{version}.jar
```

On the phone, tablet, or Mac, open **Screen Mirroring** (or AirPlay from a video app)
and pick the advertised name (default: `JavaAirPlay`).

## Configuration

Each fat jar ships its own `application.properties` (`app/gstreamer`, `app/ffmpeg`).
Override by placing `application.properties` in the working directory:

```properties
# Receiver
airplay.server-name=JavaAirPlay
airplay.width=1920
airplay.height=1080
airplay.fps=60
airplay.hls-enabled=true
airplay.mdns-loopback=false
airplay.tray-enabled=true
# Cast sender: videotest|screencapture|none  /  audiotest|none
client.video.src=videotest
client.audio.src=audiotest
# Dump (optional receive-side sidecar)
dump.enabled=false
dump.directory=dumps
dump.protocol=true
dump.video=true
dump.audio=true
dump.playlist=true
dump.artwork=true
dump.videoFps=60
```

## Playback backends

GStreamer and FFmpeg are separate apps. Install the native stack that matches the jar
or `bootRun` task you start.

### GStreamer

Install: [GStreamer documentation](https://gstreamer.freedesktop.org/documentation/installing/) · [Downloads](https://gstreamer.freedesktop.org/download/)

#### Known issues

On Windows 11 with **Smart App Control** enabled, HLS may fail to decode: the official
GStreamer runtime ships unsigned plugin DLLs, and SAC blocks them when Java loads them.
There is no per-app allowlist. Mirroring may still work if those plugins are already
trusted; for HLS on Windows use the FFmpeg backend, turn SAC off or disable hls with `airplay.hls-enabled=false`

### FFmpeg

Uses `ffplay` on `PATH`.

Install: [FFmpeg download](https://ffmpeg.org/download.html)

## Session dump

Set `dump.enabled=true` to record beside the live player under
`dumps/<timestamp>_<sessionId>/`:

- `protocol/` — RTSP/HTTP captures
- `media/` — decrypted `.h264` / remuxed `.mp4`, audio `.caf` / `.aac`
- `extras/` — HLS playlists, artwork, DMAP metadata when present

## Related projects

This monorepo unites the former:

- [java-airplay-lib](https://github.com/serezhka/java-airplay-lib)
- [java-airplay-server](https://github.com/serezhka/java-airplay-server)
- [java-airplay-server-examples](https://github.com/serezhka/java-airplay-server-examples)

## License

[MIT](http://opensource.org/licenses/MIT)

[badge-ci]: https://github.com/serezhka/java-airplay/actions/workflows/ci.yaml/badge.svg
[badge-release]: https://img.shields.io/github/v/release/serezhka/java-airplay
[badge-license]: https://img.shields.io/badge/license-MIT-blue.svg
[badge-java]: https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white
[badge-views]: https://views.whatilearened.today/views/github/serezhka/java-airplay.svg
[ci]: https://github.com/serezhka/java-airplay/actions/workflows/ci.yaml
[releases]: https://github.com/serezhka/java-airplay/releases
[license]: https://opensource.org/licenses/MIT
[openjdk]: https://openjdk.org/
