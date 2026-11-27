#!/usr/bin/env bash
# Build Jelly Holo: Rust blocker, torrent engine, yt-dlp, the licences page, then the app.
# Usage: scripts/build.sh [--install]
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
PHONE="${PHONE:-BH906DHDD1}"

if [ -d blocker ]; then
    # The lists are only for the blocker's tests; the app downloads its own
    [ -s blocker/lists/easylist.txt ] && [ -s blocker/lists/easyprivacy.txt ] || scripts/fetch-lists.sh
    (cd blocker && cargo test --quiet --release)
    (cd blocker && cargo ndk -t arm64-v8a -P 26 -o ../app/src/main/jniLibs build --release --lib)
fi

if [ -d torrent ]; then
    (cd torrent && cargo test --quiet --release)
    (cd torrent && cargo ndk -t arm64-v8a -P 26 -o ../app/src/main/jniLibs build --release --lib)
fi

scripts/fetch-ytdlp.sh
scripts/licenses.py
./gradlew --quiet assembleDebug
apk=app/build/outputs/apk/debug/app-debug.apk
echo "built $apk"

if [ "${1:-}" = "--install" ]; then
    timeout 120 adb -s "$PHONE" install -r "$apk"
fi
