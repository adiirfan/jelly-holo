#!/usr/bin/env bash
# Build Jelly Holo: block lists, Rust blocker, torrent engine, then the app.
# Usage: scripts/build.sh [--install]
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
PHONE="${PHONE:-BH906DHDD1}"

if [ -d blocker ]; then
    [ -s blocker/lists/easylist.txt ] && [ -s blocker/lists/easyprivacy.txt ] || scripts/fetch-lists.sh
    (cd blocker && cargo test --quiet --release)
    mkdir -p app/src/main/assets
    (cd blocker && cargo run --quiet --release --bin compile_lists -- \
        ../app/src/main/assets/adblock.dat lists/easylist.txt lists/easyprivacy.txt)
    (cd blocker && cargo ndk -t arm64-v8a -P 26 -o ../app/src/main/jniLibs build --release --lib)
fi

if [ -d torrent ]; then
    (cd torrent && cargo test --quiet --release)
    (cd torrent && cargo ndk -t arm64-v8a -P 26 -o ../app/src/main/jniLibs build --release --lib)
fi

scripts/fetch-ytdlp.sh
./gradlew --quiet assembleDebug
apk=app/build/outputs/apk/debug/app-debug.apk
echo "built $apk"

if [ "${1:-}" = "--install" ]; then
    timeout 120 adb -s "$PHONE" install -r "$apk"
fi
