#!/bin/sh
# Makes the Whisper model the phone app ships (Gradle runs this while it
# builds): OpenAI's Whisper small, multilingual, at 4 bits (q4_K), about
# 145 MB. It is not kept in the repository: it is made the same way every
# time, from a pinned download and the whisper.cpp in the repository, and
# checked against the pinned hash, so a Mac and F-Droid's Linux end up with
# the same file.
#
#   tools/whisper-model.sh <dir>    writes <dir>/whisper/ggml-small-q4_k.bin
#
# The download (490 MB) and the quantize tool are kept in
# ${XDG_CACHE_HOME:-~/.cache}/hubidubitask/whisper.
set -eu

out=${1:?usage: whisper-model.sh <output dir>}
here=$(cd "$(dirname "$0")" && pwd)
src="$here/../android/whisper.cpp"
cache="${XDG_CACHE_HOME:-$HOME/.cache}/hubidubitask/whisper"

# ggerganov/whisper.cpp on Hugging Face, at a fixed revision; MIT, from OpenAI.
F16_URL=https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-small.bin
F16_SHA=1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b
TYPE=q4_k
OUT_SHA=72f958dd68ec1b048d10f18ed10a365fc2322d4fd23a98a5bebedb41611d45c0
NAME=ggml-small-$TYPE.bin

sha() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

[ -f "$src/CMakeLists.txt" ] || { echo "whisper-model: $src is empty; run: git submodule update --init" >&2; exit 1; }
mkdir -p "$cache" "$out/whisper"
model="$cache/$NAME"

if [ ! -f "$model" ] || [ "$(sha "$model")" != "$OUT_SHA" ]; then
  f16="$cache/ggml-small.bin"
  if [ ! -f "$f16" ] || [ "$(sha "$f16")" != "$F16_SHA" ]; then
    echo "whisper-model: downloading Whisper small (490 MB)" >&2
    curl -fL --retry 3 -o "$f16.part" "$F16_URL"
    [ "$(sha "$f16.part")" = "$F16_SHA" ] || { echo "whisper-model: the download is not the pinned file" >&2; exit 1; }
    mv "$f16.part" "$f16"
  fi

  # The SDK's CMake and Ninja when there, else the ones on the PATH.
  sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
  cmake=cmake; gen=
  for d in "$sdk"/cmake/*/bin; do [ -x "$d/cmake" ] && cmake="$d/cmake" && PATH="$d:$PATH"; done
  command -v ninja >/dev/null 2>&1 && gen="-G Ninja"
  build="$cache/quantize-build"
  # shellcheck disable=SC2086
  "$cmake" -S "$here/whisper-quantize" -B "$build" $gen -DCMAKE_BUILD_TYPE=Release -DWHISPER_SRC="$src" >"$cache/quantize-build.log" 2>&1 \
    || { cat "$cache/quantize-build.log" >&2; exit 1; }
  "$cmake" --build "$build" --target whisper-quantize -j 4 >>"$cache/quantize-build.log" 2>&1 \
    || { tail -30 "$cache/quantize-build.log" >&2; exit 1; }

  echo "whisper-model: making the 4-bit model" >&2
  "$build/bin/whisper-quantize" "$f16" "$model.part" "$TYPE" >"$cache/quantize.log" 2>&1 \
    || { tail -20 "$cache/quantize.log" >&2; exit 1; }
  got=$(sha "$model.part")
  [ "$got" = "$OUT_SHA" ] || { echo "whisper-model: the model came out as $got, not the pinned $OUT_SHA" >&2; exit 1; }
  mv "$model.part" "$model"
fi

cp "$model" "$out/whisper/$NAME.part" && mv "$out/whisper/$NAME.part" "$out/whisper/$NAME"
