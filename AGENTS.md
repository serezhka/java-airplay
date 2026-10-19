# Agent guide (public)

Guidance for AI coding agents and human contributors. Keep this file public-safe:
no private hosts, credentials, session dumps, or lab-only war stories.

## Public repo hygiene

- Do **not** commit secrets, tokens, private IPs/hostnames, personal paths, or
  private research dumps.
- Prefer describing how to reproduce over pasting captures into the tree.
- Do not “helpfully” add `.env` / credential files. Local pairing secrets stay
  gitignored.

## Quality bar

- Small, reviewable diffs that match existing style.
- No drive-by refactors or speculative features.
- Fix root causes in application/library code. Opt-in flags for CI/self-cast
  default **off** in release `application.properties`.
- Prefer tests when behavior changes and a harness already exists
  (see `app/common/src/testFixtures/docs/playback-lifecycle.md`).

## Cross-platform product

This repo ships **two** GitHub release fat jars (`app/gstreamer`, `app/ffmpeg`).
End users install only GStreamer **or** FFmpeg per the README and run
`java -jar …`. Receiver, tray, discovery, cast, HLS, and mirror must work from
that default jar on macOS / Linux / Windows.

**Do not** treat host/lab tweaks as the product fix:

- Do not disable or reconfigure the host OS (IPv6, firewall, mDNS daemon,
  sysctl, …) as the delivered solution.
- Do not strip tray / discovery / cast to make one machine look green.
- Temporary host experiments are fine only when explicitly requested — then
  revert and land a real cross-platform code fix.

## Layering

```
protocol/      FairPlay, pairing, RTSP, mirror framing, key labels
server/        session, Netty, HLS, mDNS, Playback port
client/        cast/send, CaptureSource SPI (no gst/ffmpeg)
app/common     Spring/tray/dump shared by both fat jars (no native)
app/gstreamer  one fat jar: Playback + CaptureSource (GStreamer)
app/ffmpeg     one fat jar: Playback + CaptureSource (FFmpeg)
```

- `protocol` must not know about `server`, `client`, or any app.
- `server` depends on `Playback` (and protocol), not on which native stack is wired.
- `client` must not depend on `server` or gst/ffmpeg.
- `app/common` must not depend on gstreamer or ffmpeg libraries.
- `app/gstreamer` and `app/ffmpeg` must not depend on each other.
- Only the two app modules are Spring Boot entrypoints.

Comments and errors describe the **local** invariant, not a downstream native
crash (“drop invalid AUs before `onVideo`”, not “or GStreamer will SIGSEGV”).

## Client work must not break the server

When changing the sender / cast / tray client, do **not** modify the server
receive path (`server/**`, receive-side FairPlay decrypt used by SETUP) unless
the task explicitly includes receive-side work. A one-line decrypt or SETUP
change can black out mirroring while the cast client is being developed.

## No third-party AirPlay names

Do not name other AirPlay (or similar) implementations in source, comments,
tests, commit messages, or docs you edit here. Describe the local invariant
(feature bits, protocol behavior) instead. External `@see` links to those
projects are disallowed unless someone explicitly asks for them.

## YouTube + screen mirroring (short)

iOS / YouTube often **cache** AirPlay receiver capabilities by stable identity
(`deviceid` / MAC, `pk`), not by `serverName`. Advertising Video / HTTP-Live
bits (or leftover cache from when those bits were on) can make YouTube blank
the phone UI while still mirroring chrome — without a reliable `POST /play`.

- Prefer a fresh YouTube install when diagnosing “ghost” TV-mode behavior.
- Confirm live mDNS: if `_airplay._tcp` has no our instance, the phone may be
  showing a ghost.
- Do not flip feature bits mid-debug without clearing client cache.
- Healthy HLS after a clean client: `POST /play` + FCUP + mediadata. Mirror-only
  chrome without `/play` is the broken mode.

## HLS / VOD end-of-item (short)

When a short VOD item ends (ads, ENDLIST):

- Pin `/playback-info` at end: `duration=D`, `position=D`, **`rate=1`**.
- Do **not** storm FCUP, and do not reverse-signal lone `paused` / `loading` /
  `stopped` as the end strategy.
- Signal end with the reverse `/event` burst: `itemPlayedToEnd` →
  `stopped` with `reason=ended` → `itemRemoved` → `currentItemChanged`
  (optional leading `loading`, trailing `stopped`).
- While the clock moves: buffer flags empty=false / full=true / keepUp=true.
  At item end: empty=true / full=false.
- Live sliding windows are a different path (`loading` + FCUP until the body
  swaps).
