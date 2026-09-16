<!--
  Working draft. Same body as README.md on this branch (for side-by-side edits).
  Promote by keeping README.md; drop this file when done.
-->

<!-- markdownlint-disable-next-line -->
<div align="center">

  <!-- markdownlint-disable-next-line -->
  # Java AirPlay

  Open-source AirPlay audio + screen-mirroring receiver in Java.

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

- AirPlay audio + screen mirroring
- FairPlay decryption for mirrored streams
- Playbacks: GStreamer, FFmpeg (`ffplay`), VLC
- Optional session dump (protocol + decrypted media) for debugging

## Quick start

### From source

```shell
git clone https://github.com/serezhka/java-airplay.git
cd java-airplay
./gradlew bootRun
```

### Pre-built jar

Download the [latest release](https://github.com/serezhka/java-airplay/releases/latest), then:

```shell
java -jar java-airplay-server-{version}.jar
```

Open **Screen Mirroring** on the iPhone / iPad / Mac and pick the receiver name (default from config, e.g. `srzhka`).

## Configuration

Create `application.properties` in the working directory:

```properties
# airplay
airplay.serverName=srzhka
airplay.width=1280
airplay.height=720
airplay.fps=24
# player (gstreamer, ffmpeg, vlc)
player.implementation=gstreamer
player.tray.enabled=true
# dump (optional sidecar)
dump.enabled=false
dump.directory=dumps
dump.protocol=true
dump.video=true
dump.audio=true
dump.playlist=true
dump.artwork=true
dump.videoFps=60
```

## Playback

Pick a backend with `player.implementation` (`gstreamer`, `ffmpeg`, or `vlc`). Install the matching native player first.

### GStreamer (recommended)

Best option for video **and** audio (including AAC-ELD mirroring audio).

Install: [GStreamer documentation](https://gstreamer.freedesktop.org/documentation/installing/) · [Downloads](https://gstreamer.freedesktop.org/download/)

### FFmpeg

Uses `ffplay` on `PATH`. Video works; **mirroring audio is unreliable** on stock builds (AAC-ELD usually needs a custom ffmpeg with `libfdk-aac`). Prefer GStreamer when you need audio.

Install: [FFmpeg download](https://ffmpeg.org/download.html)

### VLC

**Very unstable** in this project (sessions often drop). Use only for experiments.

Install: [VLC download](https://www.videolan.org/vlc/)

## Session dump

Set `dump.enabled=true` to record beside the live player under `dumps/<timestamp>_<sessionId>/`:

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
