# fdk-aac WASM for `:app:mse`

Browser-side AAC-ELD / AAC-LC decode (AirPlay mirror audio). Built from
[fdk-aac](https://github.com/mstorsjo/fdk-aac) with Emscripten; decoder-only.

## Rebuild

```bash
# requires emsdk on PATH (emcc)
./tools/mse-fdk-wasm/build.sh
# copies fdk_aac.js + fdk_aac.wasm into app/mse/src/main/resources/mse/
```

Committed binaries are the release artifacts so CI does not need emscripten.
