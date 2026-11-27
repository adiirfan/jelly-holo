#!/usr/bin/env bash
# Fetch and build what "Download with yt-dlp" ships in the app:
#  - Python (python.org's Android package), trimmed, as assets/ytdlp/python.zip
#  - a tiny launcher that starts Python, as jniLibs/arm64-v8a/libytpython.so
#  - QuickJS-ng (YouTube needs a script runner), as jniLibs/arm64-v8a/libqjs.so
#  - yt-dlp itself, as assets/ytdlp/yt-dlp
# Every download is pinned and checked. Results are git-ignored.
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
NDK="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

PY_VERSION=3.14.8
PY_SHA=11324313957da3736f44e90257304d288864321a4dd376ed0cc35a54eacf9a33
YTDLP_VERSION=2026.11.23
YTDLP_SHA=e8665ed64d6b9172773e812f891fb707a0cb1ee8d9ef4fc1acb00cabee4c5256
QJS_TAG=v0.18.0

CACHE=ytdlp/cache
ASSETS=app/src/main/assets/ytdlp
LIBS=app/src/main/jniLibs/arm64-v8a
mkdir -p "$CACHE" "$ASSETS" "$LIBS"

fetch() { # url file sha
    if [ ! -s "$2" ] || ! echo "$3  $2" | sha256sum -c --quiet - 2>/dev/null; then
        curl -fsSL --max-time 300 -o "$2.part" "$1"
        echo "$3  $2.part" | sha256sum -c --quiet -
        mv "$2.part" "$2"
    fi
}

# Python
py_tar="$CACHE/python-$PY_VERSION.tar.gz"
fetch "https://www.python.org/ftp/python/$PY_VERSION/python-$PY_VERSION-aarch64-linux-android.tar.gz" "$py_tar" "$PY_SHA"
stamp="$CACHE/python-$PY_VERSION.done"
if [ ! -f "$stamp" ] || [ ! -s "$ASSETS/python.zip" ] || [ ! -s "$LIBS/libytpython.so" ]; then
    rm -rf "$CACHE/py" && mkdir -p "$CACHE/py" && tar -xzf "$py_tar" -C "$CACHE/py"
    prefix="$CACHE/py/prefix"
    "$CLANG" -O2 -I "$prefix/include/python3.14" ytdlp/launch.c -L "$prefix/lib" -lpython3.14 -s -o "$LIBS/libytpython.so"
    # Trim what yt-dlp never uses
    rm -rf "$prefix/include" "$prefix/bin" "$prefix/lib/pkgconfig"
    for d in test idlelib tkinter turtledemo ensurepip lib2to3 pydoc_data; do
        rm -rf "$prefix/lib/python3.14/$d"
    done
    find "$prefix" -name __pycache__ -type d -prune -exec rm -rf {} +
    rm -f "$ASSETS/python.zip"
    (cd "$prefix" && zip -q -r -9 "$OLDPWD/$ASSETS/python.zip" lib)
    touch "$stamp"
fi

# QuickJS-ng
if [ ! -s "$LIBS/libqjs.so" ]; then
    rm -rf "$CACHE/qjs-src" "$CACHE/qjs-build"
    timeout 300 git -c advice.detachedHead=false clone -q --depth 1 --branch "$QJS_TAG" https://github.com/quickjs-ng/quickjs.git "$CACHE/qjs-src"
    timeout 400 cmake -S "$CACHE/qjs-src" -B "$CACHE/qjs-build" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release > "$CACHE/qjs-cmake.log"
    timeout 600 cmake --build "$CACHE/qjs-build" -j8 --target qjs_exe > "$CACHE/qjs-build.log"
    "$STRIP" -o "$LIBS/libqjs.so" "$CACHE/qjs-build/qjs"
fi

# yt-dlp
fetch "https://github.com/yt-dlp/yt-dlp/releases/download/$YTDLP_VERSION/yt-dlp" "$CACHE/yt-dlp-$YTDLP_VERSION" "$YTDLP_SHA"
cp "$CACHE/yt-dlp-$YTDLP_VERSION" "$ASSETS/yt-dlp"
printf '%s' "$YTDLP_VERSION" > "$ASSETS/version"
