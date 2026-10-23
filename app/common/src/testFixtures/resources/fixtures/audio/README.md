# Audio fixtures for Playback integration tests

Committed binaries (do not generate on CI runners):

| File | Format | Use |
|------|--------|-----|
| `sine-44100-aac-lc.adts` | AAC-LC ADTS | Mirror + Music AAC path |
| `sine-44100-alac.lp` | `u32 BE` length + raw ALAC AU | Music ALAC |
| `sine-44100-aac-eld.lp` | `u32 BE` length + raw AAC-ELD AU | AAC-ELD pipeline smoke (CAF `aace`; production ASC stays `f8e85000`) |
| `sine-44100-alac.caf` / `sine-44100-aac-eld.caf` | Source CAF (regen only) | Rebuild `.lp` |

## Regenerate (macOS)

```bash
TMP=$(mktemp -d)
ffmpeg -y -f lavfi -i "sine=frequency=440:duration=1:sample_rate=44100" -ac 2 \
  -c:a aac -ar 44100 -b:a 128k -f adts sine-44100-aac-lc.adts
ffmpeg -y -f lavfi -i "sine=frequency=440:duration=1:sample_rate=44100" -ac 2 \
  -c:a alac -ar 44100 sine-44100-alac.caf
ffmpeg -y -f lavfi -i "sine=frequency=440:duration=1:sample_rate=44100" -ac 2 sine.wav
afconvert -f caff -d aace sine.wav sine-44100-aac-eld.caf
# Split CAF packets → length-prefixed .lp (see PlaybackFixture / prior session script):
# ffprobe -show_packets → sizes; slice CAF data chunk; write u32be+payload per AU.
```
