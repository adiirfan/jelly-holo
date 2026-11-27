#!/usr/bin/env bash
# Download EasyList and EasyPrivacy for the blocker's tests (the app fetches its own lists).
set -euo pipefail
cd "$(dirname "$0")/../blocker"
mkdir -p lists
for name in easylist easyprivacy; do
    timeout 120 curl -fsSL --retry 3 -o "lists/$name.txt.part" "https://easylist.to/easylist/$name.txt"
    mv "lists/$name.txt.part" "lists/$name.txt"
    echo "fetched lists/$name.txt ($(wc -l < "lists/$name.txt") lines)"
done
