#!/usr/bin/env python3
# SPDX-FileCopyrightText: Jelly Holo contributors
# SPDX-License-Identifier: MIT
"""Build the app's "Open-source licences" page (app/src/main/assets/licenses.html).

Lists everything the app ships: Jelly itself, every Rust crate linked into
the two native libraries, Python and what it bundles, yt-dlp, QuickJS-ng and
every Android library. Each part's own licence files are included; parts
without any get the standard text of their licence. Fails when an Android
library has no known licence, so a new dependency can't slip in unlisted.
"""
import hashlib
import html
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/main/assets/licenses.html")
LICENSES = os.path.join(ROOT, "LICENSES")
THIRD = os.path.join(ROOT, "licenses")
CRATES = ["blocker", "torrent"]
TARGET = "aarch64-linux-android"
LICENCE_FILE = re.compile(r"^(licen[cs]e|copying|notice|unlicense|copyright)", re.I)

# Android libraries, by group, and their licence
ANDROID = {
    "androidx": "Apache-2.0",
    "com.google.android.material": "Apache-2.0",
    "com.google.errorprone": "Apache-2.0",
    "com.google.guava": "Apache-2.0",
    "org.jetbrains": "Apache-2.0",
    "org.jetbrains.kotlin": "Apache-2.0",
    "org.jetbrains.kotlinx": "Apache-2.0",
    "org.jspecify": "Apache-2.0",
    "org.rustls": "MIT OR Apache-2.0",
}


def crates():
    """Every crate linked into the phone libraries (normal dependencies only)."""
    found = {}
    for d in CRATES:
        m = json.loads(subprocess.check_output(
            ["cargo", "metadata", "--format-version", "1", "--filter-platform", TARGET],
            cwd=os.path.join(ROOT, d)))
        packages = {p["id"]: p for p in m["packages"]}
        nodes = {n["id"]: n for n in m["resolve"]["nodes"]}
        ours = set(m["workspace_members"])
        stack, reached = list(ours), set()
        while stack:
            i = stack.pop()
            if i in reached:
                continue
            reached.add(i)
            for dep in nodes[i]["deps"]:
                if any(k["kind"] is None for k in dep["dep_kinds"]):
                    stack.append(dep["pkg"])
        for i in reached - ours:
            p = packages[i]
            found[(p["name"], p["version"])] = p
    return [found[k] for k in sorted(found)]


def android_libraries():
    out = subprocess.check_output(
        [os.path.join(ROOT, "gradlew"), "-q", ":app:dependencies", "--configuration", "releaseRuntimeClasspath"],
        cwd=ROOT, text=True, stderr=subprocess.DEVNULL)
    libs = {}
    for m in re.finditer(r"([\w.\-]+):([\w.\-]+):([\w.\-]+)(?: -> ([\w.\-]+))?", out):
        group, name, version = m.group(1), m.group(2), m.group(4) or m.group(3)
        libs[(group, name)] = version
    rows, unknown = [], []
    for (group, name), version in sorted(libs.items()):
        licence = next((v for k, v in ANDROID.items() if group == k or group.startswith(k + ".")), None)
        if licence is None:
            unknown.append(f"{group}:{name}")
        rows.append((f"{group}:{name}", version, licence))
    if unknown:
        sys.exit("No known licence for these Android libraries; add them to ANDROID in scripts/licenses.py:\n  "
                 + "\n  ".join(unknown))
    return rows


def ids(expression):
    return sorted({t for t in re.split(r"[\s()/]+", expression or "") if t and t not in ("OR", "AND", "WITH")})


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


texts = {}  # text hash -> (anchor, title, text)


def text_link(title, text):
    """Each distinct text appears once on the page; everything else links to it."""
    h = hashlib.sha256(text.strip().encode()).hexdigest()
    if h not in texts:
        texts[h] = (f"t{len(texts) + 1}", title, text)
    anchor = texts[h][0]
    return f'<a href="#{anchor}">{html.escape(title)}</a>'


def standard(licence):
    path = os.path.join(LICENSES, f"{licence}.txt")
    if not os.path.isfile(path):
        sys.exit(f"Missing standard text LICENSES/{licence}.txt")
    return text_link(licence, read(path))


def row(name, version, licence, links):
    v = f' <span class="v">{html.escape(version)}</span>' if version else ""
    joined = "".join(f" · {link}" for link in links)
    return f'<li><b>{html.escape(name)}</b>{v}<br><span class="l">{html.escape(licence)}</span>{joined}</li>'


def main():
    sections = []

    jelly = [row("LineageOS Jelly", "", "Apache-2.0 · © The LineageOS Project", [standard("Apache-2.0")]),
             row("Jelly Holo's own Rust code (ad blocker, torrent engine)", "", "MIT · © Jelly Holo contributors",
                 [text_link("Jelly Holo MIT licence", read(os.path.join(ROOT, "blocker/LICENSE")))])]
    sections.append(("This app", jelly))

    bundled = [
        row("Python", "3.14.8", "PSF-2.0, with the licences of the parts it includes",
            [text_link("Python licence and incorporated software", read(os.path.join(THIRD, "python-3.14.8-license.rst")))]),
        row("OpenSSL (inside Python)", "3.5", "Apache-2.0 · © The OpenSSL Project Authors", [standard("Apache-2.0")]),
        row("SQLite (inside Python)", "", "Public domain", []),
        row("bzip2 (inside Python)", "1.0.8", "bzip2-1.0.6",
            [text_link("bzip2 LICENSE", read(os.path.join(THIRD, "bzip2-1.0.8-LICENSE")))]),
        row("XZ Utils liblzma (inside Python)", "5.4.6", "Public domain",
            [text_link("XZ Utils COPYING", read(os.path.join(THIRD, "xz-5.4.6-COPYING")))]),
        row("yt-dlp", "2026.11.23", "Unlicense", [text_link("yt-dlp LICENSE", read(os.path.join(THIRD, "yt-dlp-LICENSE")))]),
        row("QuickJS-ng", "0.18.0", "MIT", [text_link("QuickJS-ng LICENSE", read(os.path.join(THIRD, "quickjs-ng-LICENSE")))]),
    ]
    sections.append(("Video saving", bundled))

    rust = []
    for p in crates():
        folder = os.path.dirname(p["manifest_path"])
        files = sorted(f for f in os.listdir(folder) if LICENCE_FILE.match(f) and os.path.isfile(os.path.join(folder, f)))
        if files:
            links = [text_link(f"{p['name']} {f}", read(os.path.join(folder, f))) for f in files]
        else:
            links = [standard(i) for i in ids(p.get("license"))]
            if p.get("authors"):
                links.append("© " + html.escape(", ".join(p["authors"])))
        rust.append(row(p["name"], p["version"], p.get("license") or "see files", links))
    sections.append((f"Rust libraries ({len(rust)})", rust))

    android = [row(name, version, licence, [standard(i) for i in ids(licence)])
               for name, version, licence in android_libraries()]
    sections.append((f"Android libraries ({len(android)})", android))

    body = []
    for title, rows in sections:
        body.append(f"<h2>{html.escape(title)}</h2><ul>{''.join(rows)}</ul>")
    body.append("<h2>Licence texts</h2>")
    for anchor, title, text in texts.values():
        body.append(f'<details id="{anchor}"><summary>{html.escape(title)}</summary><pre>{html.escape(text)}</pre></details>')

    page = f"""<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>Open-source licences</title>
<style>
body{{font:15px/1.45 sans-serif;margin:0 16px 32px;}}
h2{{font-size:17px;margin:24px 0 8px;color:#33b5e5;}}
ul{{list-style:none;padding:0;margin:0;}}
li{{padding:8px 0;border-bottom:1px solid rgba(128,128,128,.25);overflow-wrap:anywhere;}}
.v,.l{{opacity:.7;font-size:13px;}}
a{{color:#33b5e5;}}
details{{border-bottom:1px solid rgba(128,128,128,.25);padding:8px 0;}}
details:target{{background:rgba(51,181,229,.12);}}
pre{{white-space:pre-wrap;font-size:12px;overflow-wrap:anywhere;}}
</style></head><body>
<p>Jelly Holo is built on LineageOS Jelly and includes the software below. Tap a licence name to read it.</p>
{''.join(body)}
<script>
// Opening a licence from the list unfolds it
addEventListener('hashchange', () => {{ const d = document.querySelector(location.hash); if (d) d.open = true; }});
</script>
</body></html>
"""
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(page)
    print(f"wrote {os.path.relpath(OUT, ROOT)}: {len(rust)} Rust crates, {len(android)} Android libraries, "
          f"{len(texts)} licence texts, {len(page) // 1024} KB")


if __name__ == "__main__":
    main()
