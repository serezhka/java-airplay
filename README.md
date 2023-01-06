# Java AirPlay Server

[![build](https://github.com/serezhka/java-airplay/actions/workflows/build.yaml/badge.svg)](https://github.com/serezhka/java-airplay/actions/workflows/build.yaml)
![ViewCount](https://views.whatilearened.today/views/github/serezhka/java-airplay.svg)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](http://opensource.org/licenses/MIT)

This project unites the [java-airplay-lib](https://github.com/serezhka/java-airplay-lib), [java-airplay-server](https://github.com/serezhka/java-airplay-server)
and [java-airplay-server-examples](https://github.com/serezhka/java-airplay-server-examples) into one.
It makes development a lot easier when all parts of the code are put together.
Also, it's still possible to publish the airplay-lib separately as artifact if someone wants to implement non-Netty server solution;
to publish airplay-server if someone wants to implement their own player.

## Demo

* Raspberry pi 4 model B (1280 x 720 @ 24 fps)

[![RASPBERRY](https://img.youtube.com/vi/uRvgVkLWfSI/hqdefault.jpg)](https://youtu.be/uRvgVkLWfSI)

* Windows laptop (1920 x 1080 @ 30 fps)

[![RASPBERRY](https://img.youtube.com/vi/RT1hVWGJzos/hqdefault.jpg)](https://youtu.be/RT1hVWGJzos)

## How to Run

```shell
git clone https://github.com/serezhka/java-airplay
cd ./java-airplay
./gradlew bootRun
```

## Configuration

#### **`application.properties`**
```properties
# airplay
airplay.serverName=srzhka
airplay.airplayPort=15614
airplay.airtunesPort=5001
airplay.width=1280
airplay.height=720
airplay.fps=24
# player (gstreamer, h264-dump)
player.implementation=gstreamer
player.menu.enabled=true
player.gstreamer.swing=true
```

## Players

### Gstreamer

Supports both video and audio (alac + aac_eld) streams <br>
Gstreamer installation is required (see https://github.com/gstreamer-java/gst1-java-core)

### h264-dump

Saves video stream into dump.h264 file