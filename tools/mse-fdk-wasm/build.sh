#!/usr/bin/env bash
# Rebuild fdk-aac decoder WASM and copy into :app:mse resources.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$(cd "$(dirname "$0")" && pwd)/out"
WRAPPER="$(cd "$(dirname "$0")" && pwd)/fdk_eld_wasm.c"
DEST="$ROOT/app/mse/src/main/resources/mse"
mkdir -p "$OUT"

if ! command -v emcc >/dev/null 2>&1; then
  echo "emcc not on PATH — source emsdk_env.sh first" >&2
  exit 1
fi

FDK_SRC="${FDK_AAC_SRC:-}"
if [ -z "$FDK_SRC" ]; then
  WORK="$OUT/fdk-aac"
  if [ ! -d "$WORK/.git" ]; then
    git clone --depth 1 https://github.com/mstorsjo/fdk-aac.git "$WORK"
  fi
  FDK_SRC="$WORK"
fi

: > "$OUT/sources.rsp"
find "$FDK_SRC/libAACdec/src" "$FDK_SRC/libArithCoding/src" "$FDK_SRC/libDRCdec/src" \
  "$FDK_SRC/libFDK/src" "$FDK_SRC/libMpegTPDec/src" "$FDK_SRC/libPCMutils/src" \
  "$FDK_SRC/libSACdec/src" "$FDK_SRC/libSBRdec/src" "$FDK_SRC/libSYS/src" -name '*.cpp' \
  ! -path '*/arm/*' ! -path '*/mips/*' ! -path '*/x86/*' \
  >> "$OUT/sources.rsp"

INCLUDES=(
  -I"$FDK_SRC/libAACdec/include" -I"$FDK_SRC/libSYS/include"
  -I"$FDK_SRC/libArithCoding/include" -I"$FDK_SRC/libDRCdec/include"
  -I"$FDK_SRC/libSACdec/include" -I"$FDK_SRC/libSBRdec/include"
  -I"$FDK_SRC/libMpegTPDec/include" -I"$FDK_SRC/libFDK/include" -I"$FDK_SRC/libPCMutils/include"
)

emcc "$WRAPPER" @"$OUT/sources.rsp" "${INCLUDES[@]}" \
  -O2 -fno-exceptions -fno-rtti \
  -s WASM=1 -s MODULARIZE=1 -s EXPORT_NAME=createFdkAacModule \
  -s ALLOW_MEMORY_GROWTH=1 \
  -s EXPORTED_FUNCTIONS='["_fdk_open","_fdk_decode","_fdk_close","_fdk_channels","_fdk_frame_samples","_malloc","_free"]' \
  -s EXPORTED_RUNTIME_METHODS='["cwrap","HEAPU8","HEAP16","getValue"]' \
  -o "$OUT/fdk_aac.js"

cp "$OUT/fdk_aac.js" "$OUT/fdk_aac.wasm" "$DEST/"
ls -lh "$DEST/fdk_aac.js" "$DEST/fdk_aac.wasm"
echo "OK — copied into $DEST"
