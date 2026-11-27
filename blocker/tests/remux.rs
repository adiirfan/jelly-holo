//! Tests for the stream joiner (MPEG-TS pieces to one MP4), on clips made by
//! ffmpeg. Needs ffmpeg and ffprobe on this machine.

use std::fs::{self, File};
use std::path::{Path, PathBuf};
use std::process::Command;

use jellyblock::remux::{fmp4_to_mp4, h264_picture_size, ts_to_mp4, RemuxError};

fn dir(name: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("jhremux-{}-{name}", std::process::id()));
    let _ = fs::remove_dir_all(&d);
    fs::create_dir_all(&d).unwrap();
    d
}

fn ffmpeg(args: &[&str]) {
    let ok = Command::new("ffmpeg").args(["-loglevel", "error", "-y"]).args(args).status().unwrap();
    assert!(ok.success(), "ffmpeg failed: {args:?}");
}

const PICTURE: &[&str] = &["-f", "lavfi", "-i", "testsrc=size=1280x720:rate=25"];
const SOUND: &[&str] = &["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100"];
const H264: &[&str] = &["-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", "main", "-preset", "veryfast", "-g", "50", "-bf", "3"];
const AAC: &[&str] = &["-c:a", "aac", "-b:a", "96k"];

/// A clip of [secs] seconds as one TS file.
fn make_ts(path: &Path, secs: &str, extra: &[&str]) {
    let mut a: Vec<&str> = vec![];
    a.extend(PICTURE);
    a.extend(SOUND);
    a.extend(["-t", secs]);
    a.extend(H264);
    a.extend(AAC);
    a.extend(extra);
    a.extend(["-f", "mpegts", path.to_str().unwrap()]);
    ffmpeg(&a);
}

/// Presentation times (seconds) of one kind of track's packets, sorted.
fn times(file: &Path, kind: &str) -> Vec<f64> {
    let out = Command::new("ffprobe")
        .args(["-v", "error", "-select_streams", kind, "-show_entries", "packet=pts_time", "-of", "csv=p=0"])
        .arg(file)
        .output()
        .unwrap();
    let mut t: Vec<f64> = String::from_utf8_lossy(&out.stdout)
        .lines()
        .filter_map(|l| l.trim().trim_end_matches(',').parse().ok())
        .collect();
    t.sort_by(|a, b| a.partial_cmp(b).unwrap());
    t
}

/// ffmpeg's complaints when decoding the whole file (empty when clean).
fn decode_warnings(file: &Path) -> String {
    let out = Command::new("ffmpeg").args(["-v", "warning", "-i"]).arg(file).args(["-f", "null", "-"]).output().unwrap();
    String::from_utf8_lossy(&out.stderr).into_owned()
}

fn remux(video: &Path, audio: Option<&Path>, out: &Path) -> Result<(), RemuxError> {
    let mut f = File::create(out).unwrap();
    ts_to_mp4(video, audio, &mut f).map(|_| ())
}

/// Every picture and every sound frame from the first picture on is in the
/// MP4, each at its source time measured from the first picture.
fn assert_same_timeline(src_video: &[f64], src_audio: &[f64], out: &Path) {
    let start = src_video[0];
    let got_v = times(out, "v");
    assert_eq!(got_v.len(), src_video.len(), "picture frames");
    for (g, s) in got_v.iter().zip(src_video) {
        assert!((g - (s - start)).abs() < 0.002, "picture at {g}, source {}", s - start);
    }
    // Sound frames that start before the first picture are left out
    let want_a: Vec<f64> = src_audio.iter().filter(|&&t| t >= start - 0.0005).map(|t| t - start).collect();
    let got_a = times(out, "a");
    assert_eq!(got_a.len(), want_a.len(), "sound frames");
    for (g, w) in got_a.iter().zip(&want_a) {
        assert!((g - w).abs() < 0.002, "sound at {g}, source {w}");
    }
    let warnings = decode_warnings(out);
    assert!(warnings.is_empty(), "decoder complained: {warnings}");
}

#[test]
fn muxed_ts_becomes_mp4_with_every_frame_on_time() {
    let d = dir("muxed");
    let ts = d.join("in.ts");
    make_ts(&ts, "4", &[]);
    let out = d.join("out.mp4");
    remux(&ts, None, &out).unwrap();
    assert_same_timeline(&times(&ts, "v"), &times(&ts, "a"), &out);
}

#[test]
fn pieces_with_restarting_counters_lose_nothing() {
    let d = dir("pieces");
    let mut a: Vec<&str> = vec![];
    a.extend(PICTURE);
    a.extend(SOUND);
    a.extend(["-t", "9"]);
    a.extend(H264);
    a.extend(AAC);
    let seg = d.join("p%03d.ts");
    let list = d.join("list.m3u8");
    a.extend(["-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod", "-hls_segment_filename", seg.to_str().unwrap(), list.to_str().unwrap()]);
    ffmpeg(&a);
    let mut pieces: Vec<PathBuf> = fs::read_dir(&d).unwrap().map(|e| e.unwrap().path()).filter(|p| p.extension().is_some_and(|e| e == "ts")).collect();
    pieces.sort();
    assert!(pieces.len() >= 4);
    // As many servers do: every piece counts its packets from 0 again
    let mut joined = vec![];
    for p in &pieces {
        let mut b = fs::read(p).unwrap();
        let mut next = std::collections::HashMap::new();
        for at in (0..b.len()).step_by(188) {
            let pid = (((b[at + 1] & 0x1f) as u16) << 8) | b[at + 2] as u16;
            if b[at + 3] & 0x10 != 0 {
                let cc = next.entry(pid).or_insert(0u8);
                b[at + 3] = (b[at + 3] & 0xf0) | *cc;
                *cc = (*cc + 1) & 15;
            }
        }
        joined.extend(b);
    }
    let ts = d.join("joined.ts");
    fs::write(&ts, &joined).unwrap();
    let out = d.join("out.mp4");
    remux(&ts, None, &out).unwrap();
    assert_same_timeline(&times(&ts, "v"), &times(&ts, "a"), &out);
}

#[test]
fn separate_sound_file_is_joined_in_step() {
    let d = dir("separate");
    let both = d.join("both.ts");
    make_ts(&both, "5", &[]);
    let v = d.join("v.ts");
    let a = d.join("a.ts");
    ffmpeg(&["-i", both.to_str().unwrap(), "-map", "0:v", "-c", "copy", "-copyts", "-f", "mpegts", v.to_str().unwrap()]);
    ffmpeg(&["-i", both.to_str().unwrap(), "-map", "0:a", "-c", "copy", "-copyts", "-f", "mpegts", a.to_str().unwrap()]);
    let out = d.join("out.mp4");
    remux(&v, Some(&a), &out).unwrap();
    assert_same_timeline(&times(&v, "v"), &times(&a, "a"), &out);
}

#[test]
fn picture_only_stream_works() {
    let d = dir("silent");
    let ts = d.join("in.ts");
    make_ts(&ts, "3", &["-an"]);
    let out = d.join("out.mp4");
    remux(&ts, None, &out).unwrap();
    assert_eq!(times(&out, "v").len(), times(&ts, "v").len());
    assert!(times(&out, "a").is_empty());
}

#[test]
fn the_streams_clock_rolling_over_is_followed() {
    // TS clocks count to 2^33 / 90 kHz, about 95443.7 s, then start again at 0
    let d = dir("wrap");
    let ts = d.join("in.ts");
    make_ts(&ts, "8", &["-output_ts_offset", "95440"]);
    let src_v = times(&ts, "v");
    // ffprobe counts from the roll-over: times before it come out negative
    assert!(src_v.iter().any(|&t| t < 0.0) && src_v.iter().any(|&t| t > 0.0), "fixture doesn't roll over");
    let out = d.join("out.mp4");
    remux(&ts, None, &out).unwrap();
    let got = times(&out, "v");
    assert_eq!(got.len(), src_v.len());
    let span = got.last().unwrap() - got[0];
    assert!((span - 7.96).abs() < 0.05, "span {span}");
    assert!(decode_warnings(&out).is_empty());
}

#[test]
fn picture_sizes_come_from_the_picture_settings() {
    let d = dir("sizes");
    for (size, profile) in [("1920x1080", "high"), ("1280x720", "main"), ("640x360", "baseline"), ("854x480", "high")] {
        let f = d.join(format!("{size}.h264"));
        ffmpeg(&["-f", "lavfi", "-i", &format!("testsrc=size={size}:rate=25"), "-t", "0.2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", profile, "-f", "h264", f.to_str().unwrap()]);
        let data = fs::read(&f).unwrap();
        // The first SPS (NAL type 7) after a start code
        let start = data.windows(4).position(|w| w == [0, 0, 1, 0x67] || (w[..3] == [0, 0, 1] && w[3] & 0x1f == 7)).unwrap() + 3;
        let end = data[start..].windows(3).position(|w| w == [0, 0, 1]).map(|e| start + e).unwrap();
        let mut sps = data[start..end].to_vec();
        while sps.last() == Some(&0) {
            sps.pop();
        }
        let (w, h) = size.split_once('x').unwrap();
        assert_eq!(h264_picture_size(&sps), Some((w.parse().unwrap(), h.parse().unwrap())), "{size} {profile}");
    }
}

#[test]
fn h265_is_left_to_android() {
    let d = dir("hevc");
    let ts = d.join("in.ts");
    let ok = Command::new("ffmpeg")
        .args(["-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25", "-t", "1", "-c:v", "libx265", "-f", "mpegts"])
        .arg(&ts)
        .status()
        .unwrap();
    if !ok.success() {
        eprintln!("no libx265 here; skipped");
        return;
    }
    assert!(matches!(remux(&ts, None, &d.join("out.mp4")), Err(RemuxError::Unsupported(_))));
}

#[test]
fn something_that_is_not_a_stream_is_refused() {
    let d = dir("junk");
    let f = d.join("junk.ts");
    fs::write(&f, b"<html>not a video</html>".repeat(40)).unwrap();
    assert!(matches!(remux(&f, None, &d.join("out.mp4")), Err(RemuxError::NotMine)));
}

// ---- Fragmented MP4 pieces (the newer HLS format) ----

/// An fMP4 HLS stream of [secs] seconds made with [video] settings; returns the
/// joined picture file (init piece + pieces) and, when [separate], the sound file.
fn make_fmp4(d: &Path, video: &[&str], separate: bool, secs: &str) -> (PathBuf, Option<PathBuf>) {
    let mut a: Vec<&str> = vec![];
    a.extend(PICTURE);
    a.extend(SOUND);
    a.extend(["-t", secs, "-map", "0:v", "-map", "1:a"]);
    a.extend(video);
    a.extend(AAC);
    let seg = d.join("s%v_%03d.m4s");
    let list = d.join("p%v.m3u8");
    let map = if separate { "v:0,agroup:aud a:0,agroup:aud" } else { "v:0,a:0" };
    a.extend(["-f", "hls", "-hls_segment_type", "fmp4", "-hls_time", "2", "-hls_playlist_type", "vod",
        "-hls_fmp4_init_filename", "init_%v.mp4", "-hls_segment_filename", seg.to_str().unwrap(),
        "-master_pl_name", "master.m3u8", "-var_stream_map", map, list.to_str().unwrap()]);
    ffmpeg(&a);
    let join = |v: u32| {
        let mut pieces: Vec<PathBuf> = fs::read_dir(d).unwrap().map(|e| e.unwrap().path())
            .filter(|p| p.file_name().unwrap().to_str().unwrap().starts_with(&format!("s{v}_"))).collect();
        pieces.sort();
        // With a single stream ffmpeg leaves "%v" in the init piece's name
        let init = [d.join(format!("init_{v}.mp4")), d.join("init_%v.mp4")].into_iter().find(|p| p.exists()).unwrap();
        let mut all = fs::read(init).unwrap();
        for p in pieces {
            all.extend(fs::read(p).unwrap());
        }
        let out = d.join(format!("joined_{v}.mp4"));
        fs::write(&out, all).unwrap();
        out
    };
    (join(0), if separate { Some(join(1)) } else { None })
}

fn codec(file: &Path, kind: &str) -> String {
    let out = Command::new("ffprobe")
        .args(["-v", "error", "-select_streams", kind, "-show_entries", "stream=codec_name", "-of", "csv=p=0"])
        .arg(file)
        .output()
        .unwrap();
    String::from_utf8_lossy(&out.stdout).trim().to_string()
}

/// Every frame of both tracks shows at the same moment as in the source,
/// counted from the first picture.
fn assert_matches_source(src_v: &[f64], src_a: &[f64], out: &Path) {
    let start = src_v[0];
    for (kind, src) in [("v", src_v), ("a", src_a)] {
        let got = times(out, kind);
        assert_eq!(got.len(), src.len(), "{kind} frames");
        for (g, s) in got.iter().zip(src) {
            assert!((g - (s - start)).abs() < 0.002, "{kind} at {g}, source {}", s - start);
        }
    }
    let warnings = decode_warnings(out);
    assert!(warnings.is_empty(), "decoder complained: {warnings}");
}

fn fmp4(video: Vec<&str>, separate: bool, name: &str) {
    let d = dir(name);
    let (v, a) = make_fmp4(&d, &video, separate, "5");
    let out = d.join("out.mp4");
    let mut f = File::create(&out).unwrap();
    fmp4_to_mp4(&v, a.as_deref(), &mut f).unwrap();
    let a = a.unwrap_or_else(|| v.clone());
    assert_matches_source(&times(&v, "v"), &times(&a, "a"), &out);
    assert_eq!(codec(&out, "v"), codec(&v, "v"));
    assert_eq!(codec(&out, "a"), "aac");
}

#[test]
fn fmp4_with_sound_in_the_same_pieces() {
    fmp4(H264.to_vec(), false, "fm-muxed");
}

#[test]
fn fmp4_with_sound_in_separate_pieces() {
    fmp4(H264.to_vec(), true, "fm-separate");
}

#[test]
fn fmp4_h265_keeps_its_format() {
    fmp4(vec!["-c:v", "libx265", "-tag:v", "hvc1", "-pix_fmt", "yuv420p", "-preset", "ultrafast", "-x265-params", "log-level=error"], true, "fm-hevc");
}

#[test]
fn fmp4_vp9_keeps_its_format() {
    fmp4(vec!["-c:v", "libvpx-vp9", "-pix_fmt", "yuv420p", "-b:v", "400k", "-deadline", "realtime", "-cpu-used", "8"], true, "fm-vp9");
}

#[test]
fn fmp4_av1_keeps_its_format() {
    fmp4(vec!["-c:v", "libsvtav1", "-pix_fmt", "yuv420p", "-preset", "12", "-svtav1-params", "verbosity=0"], true, "fm-av1");
}

#[test]
fn encrypted_fmp4_is_refused() {
    let d = dir("fm-locked");
    let f = d.join("locked.mp4");
    let mut a: Vec<&str> = vec![];
    a.extend(PICTURE);
    a.extend(["-t", "2"]);
    a.extend(H264);
    a.extend(["-encryption_scheme", "cenc-aes-ctr", "-encryption_key", "00112233445566778899aabbccddeeff",
        "-encryption_kid", "ffeeddccbbaa99887766554433221100", "-movflags", "frag_keyframe+empty_moov+default_base_moof",
        "-f", "mp4", f.to_str().unwrap()]);
    ffmpeg(&a);
    let mut out = File::create(d.join("out.mp4")).unwrap();
    assert!(matches!(fmp4_to_mp4(&f, None, &mut out), Err(RemuxError::Unsupported(_))));
}

#[test]
fn ts_is_not_fmp4() {
    let d = dir("fm-ts");
    let ts = d.join("in.ts");
    make_ts(&ts, "1", &[]);
    let mut out = File::create(d.join("out.mp4")).unwrap();
    assert!(matches!(fmp4_to_mp4(&ts, None, &mut out), Err(RemuxError::NotMine)));
}
