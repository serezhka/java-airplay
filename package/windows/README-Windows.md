# JavaAirPlay for Windows

This package contains a bundled Java runtime. You do not need to install Java.

## Run

1. Extract `JavaAirPlay-windows-x64.zip`.
2. Start `JavaAirPlay/JavaAirPlay.exe`.
3. Allow Windows Firewall access for private networks when prompted.
4. On the iPhone, connect to the same Wi-Fi/LAN, open Screen Mirroring, then choose `JavaAirPlay`.

## GStreamer

The default configuration uses the GStreamer player because it supports video and audio.

Install the 64-bit MSVC runtime from:

https://gstreamer.freedesktop.org/download/#windows

After installing GStreamer, restart `JavaAirPlay.exe`. The app looks for the standard
`GSTREAMER_1_0_ROOT_MSVC_X86_64` environment variable created by the installer.

## Configuration

Edit `application.properties` in this folder before starting the app.

Useful options:

```properties
airplay.serverName=JavaAirPlay
airplay.width=1920
airplay.height=1080
airplay.fps=30
player.implementation=gstreamer
player.gstreamer.swing=true
```

For a quick connection test without playback, set:

```properties
player.implementation=h264-dump
```

That mode writes the mirrored H.264 stream to `dump.h264`.

## Notes

- This upstream project is not actively maintained.
- Current iOS versions may change AirPlay behavior, so discovery and connection can depend on iOS version, firewall, router multicast/mDNS settings, and GStreamer installation.
- If the iPhone cannot see the receiver, check that both devices are on the same network and that the router allows multicast/Bonjour/mDNS.
