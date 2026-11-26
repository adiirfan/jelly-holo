#!/usr/bin/env bash
# Download the block lists the build compiles into the app.
set -euo pipefail
cd "$(dirname "$0")/../blocker"
mkdir -p lists
for name in easylist easyprivacy; do
    timeout 120 curl -fsSL --retry 3 -o "lists/$name.txt.part" "https://easylist.to/easylist/$name.txt"
    mv "lists/$name.txt.part" "lists/$name.txt"
    echo "fetched lists/$name.txt ($(wc -l < "lists/$name.txt") lines)"
done
