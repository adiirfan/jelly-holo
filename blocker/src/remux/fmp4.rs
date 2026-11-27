//! Joins fragmented-MP4 stream pieces (an init piece, then pieces of
//! moof + mdat) into one ordinary MP4. Frames are copied as they are, with
//! the sample entry taken from the init piece, so the picture format doesn't
//! matter: H.264, H.265, VP9 and AV1 all work the same way.

use std::fs::File;
use std::io::{self, BufWriter, Read, Seek, Write};
use std::os::unix::fs::FileExt;
use std::path::Path;

use super::mp4::{Format, Track, Writer};
use super::{RemuxError, Stats};

/// What the init piece says about one track.
#[derive(Clone)]
struct Source {
    id: u32,
    video: bool,
    timescale: u32,
    entry: Vec<u8>,
    width: u32,
    height: u32,
    /// Where showing starts in the track's own time (edit list)
    media_time: i64,
    /// Seconds before the track starts (empty edit)
    empty: f64,
    default_duration: u32,
    default_size: u32,
    default_flags: u32,
}

struct Sample {
    offset: u64,
    size: u32,
    duration: u32,
    composition: i32,
    sync: bool,
}

struct Fragment {
    track: u32,
    /// Decode time of the first sample, in the track's units
    start: u64,
    samples: Vec<Sample>,
}

/// Reads one file's boxes in order, keeping only the fragments of the tracks wanted.
struct Reader {
    file: File,
    pos: u64,
    len: u64,
    sources: Vec<Source>,
    next_time: std::collections::HashMap<u32, u64>,
    queue: std::collections::VecDeque<Fragment>,
}

impl Reader {
    fn open(path: &Path) -> Result<Reader, RemuxError> {
        let mut file = File::open(path)?;
        let len = file.metadata()?.len();
        let mut head = [0u8; 8];
        if len < 8 || file.read_exact(&mut head).is_err() || !matches!(&head[4..8], b"ftyp" | b"styp" | b"moov" | b"sidx") {
            return Err(RemuxError::NotMine);
        }
        let mut r = Reader { file, pos: 0, len, sources: vec![], next_time: Default::default(), queue: Default::default() };
        // The init piece comes first: read up to its moov
        while r.sources.is_empty() {
            let Some((kind, start, body, end)) = r.next_box()? else { return Err(RemuxError::NotMine) };
            match &kind {
                b"moov" => {
                    let moov = r.read(body, end)?;
                    r.sources = parse_moov(&moov)?;
                    if r.sources.is_empty() {
                        return Err(RemuxError::NoPicture);
                    }
                }
                b"moof" => return Err(RemuxError::NotMine),
                _ => {}
            }
            let _ = start;
        }
        Ok(r)
    }

    fn read(&self, from: u64, to: u64) -> io::Result<Vec<u8>> {
        let mut b = vec![0u8; (to - from) as usize];
        self.file.read_exact_at(&mut b, from)?;
        Ok(b)
    }

    /// The next top-level box: (type, start, body start, end).
    fn next_box(&mut self) -> io::Result<Option<([u8; 4], u64, u64, u64)>> {
        if self.pos + 8 > self.len {
            return Ok(None);
        }
        let start = self.pos;
        let h = self.read(start, start + 8)?;
        let mut size = u32::from_be_bytes(h[0..4].try_into().unwrap()) as u64;
        let kind: [u8; 4] = h[4..8].try_into().unwrap();
        let mut body = start + 8;
        if size == 1 {
            if start + 16 > self.len {
                return Ok(None);
            }
            size = u64::from_be_bytes(self.read(start + 8, start + 16)?.try_into().unwrap());
            body = start + 16;
        } else if size == 0 {
            size = self.len - start;
        }
        if size < body - start {
            return Ok(None); // damaged: stop here
        }
        let end = (start + size).min(self.len);
        self.pos = end;
        Ok(Some((kind, start, body, end)))
    }

    fn source(&self, id: u32) -> Option<&Source> {
        self.sources.iter().find(|s| s.id == id)
    }

    /// The next fragment of a track in `wanted`.
    fn next(&mut self, wanted: &[u32]) -> Result<Option<Fragment>, RemuxError> {
        loop {
            if let Some(f) = self.queue.pop_front() {
                if wanted.contains(&f.track) {
                    return Ok(Some(f));
                }
                continue;
            }
            let Some((kind, start, body, end)) = self.next_box()? else { return Ok(None) };
            if &kind == b"moof" {
                let moof = self.read(body, end)?;
                let frags = self.parse_moof(&moof, start);
                self.queue.extend(frags);
            }
        }
    }

    fn parse_moof(&mut self, moof: &[u8], moof_start: u64) -> Vec<Fragment> {
        let mut out = vec![];
        for (kind, traf) in boxes(moof) {
            if &kind != b"traf" {
                continue;
            }
            let Some(tfhd) = child(traf, b"tfhd") else { continue };
            let Some(tf) = tfhd.get(..8) else { continue };
            let flags = u32::from_be_bytes(tf[0..4].try_into().unwrap()) & 0xff_ffff;
            let id = u32::from_be_bytes(tf[4..8].try_into().unwrap());
            let Some(src) = self.source(id).cloned() else { continue };
            let mut at = 8;
            let mut field = |on: bool, n: usize| -> Option<u64> {
                if !on {
                    return None;
                }
                let v = tfhd.get(at..at + n).map(|b| b.iter().fold(0u64, |a, &x| (a << 8) | x as u64));
                at += n;
                v
            };
            let base_offset = field(flags & 0x1 != 0, 8);
            field(flags & 0x2 != 0, 4);
            let duration = field(flags & 0x8 != 0, 4).map(|v| v as u32).unwrap_or(src.default_duration);
            let size = field(flags & 0x10 != 0, 4).map(|v| v as u32).unwrap_or(src.default_size);
            let sample_flags = field(flags & 0x20 != 0, 4).map(|v| v as u32).unwrap_or(src.default_flags);
            let base = base_offset.unwrap_or(moof_start);
            let start = child(traf, b"tfdt")
                .and_then(|t| match t.first()? {
                    1 => Some(u64::from_be_bytes(t.get(4..12)?.try_into().ok()?)),
                    _ => Some(u32::from_be_bytes(t.get(4..8)?.try_into().ok()?) as u64),
                })
                .unwrap_or_else(|| self.next_time.get(&id).copied().unwrap_or(0));
            let mut samples = vec![];
            let mut pos = base;
            for (k, trun) in boxes(traf) {
                if &k != b"trun" {
                    continue;
                }
                if let Some(s) = parse_trun(trun, &mut pos, base, duration, size, sample_flags, src.video) {
                    samples.extend(s);
                }
            }
            let total: u64 = samples.iter().map(|s| s.duration as u64).sum();
            self.next_time.insert(id, start + total);
            if !samples.is_empty() {
                out.push(Fragment { track: id, start, samples });
            }
        }
        out
    }
}

fn parse_trun(t: &[u8], pos: &mut u64, base: u64, duration: u32, size: u32, flags: u32, video: bool) -> Option<Vec<Sample>> {
    let version = *t.first()?;
    let tf = u32::from_be_bytes(t.get(0..4)?.try_into().ok()?) & 0xff_ffff;
    let count = u32::from_be_bytes(t.get(4..8)?.try_into().ok()?) as usize;
    let mut at = 8;
    let u32_at = |at: &mut usize| -> Option<u32> {
        let v = u32::from_be_bytes(t.get(*at..*at + 4)?.try_into().ok()?);
        *at += 4;
        Some(v)
    };
    if tf & 0x1 != 0 {
        *pos = (base as i64 + u32_at(&mut at)? as i32 as i64) as u64;
    }
    let first_flags = if tf & 0x4 != 0 { Some(u32_at(&mut at)?) } else { None };
    let mut out = Vec::with_capacity(count);
    for i in 0..count {
        let d = if tf & 0x100 != 0 { u32_at(&mut at)? } else { duration };
        let s = if tf & 0x200 != 0 { u32_at(&mut at)? } else { size };
        let mut f = if tf & 0x400 != 0 { u32_at(&mut at)? } else { flags };
        if i == 0 {
            if let Some(ff) = first_flags {
                f = ff;
            }
        }
        let c = if tf & 0x800 != 0 {
            let v = u32_at(&mut at)?;
            // Version 0 says unsigned, but values past i32 only come from encoders
            // meaning a negative offset, so both read the same way
            let _ = version;
            v as i32
        } else {
            0
        };
        out.push(Sample { offset: *pos, size: s, duration: d, composition: c, sync: !video || f & 0x0001_0000 == 0 });
        *pos += s as u64;
    }
    Some(out)
}

/// The boxes directly inside `b`.
fn boxes(b: &[u8]) -> impl Iterator<Item = ([u8; 4], &[u8])> {
    let mut at = 0usize;
    std::iter::from_fn(move || {
        if at + 8 > b.len() {
            return None;
        }
        let mut size = u32::from_be_bytes(b[at..at + 4].try_into().unwrap()) as usize;
        let kind: [u8; 4] = b[at + 4..at + 8].try_into().unwrap();
        let mut head = 8;
        if size == 1 {
            size = u64::from_be_bytes(b.get(at + 8..at + 16)?.try_into().unwrap()) as usize;
            head = 16;
        } else if size == 0 {
            size = b.len() - at;
        }
        if size < head || at + size > b.len() {
            return None;
        }
        let body = &b[at + head..at + size];
        at += size;
        Some((kind, body))
    })
}

fn child<'a>(b: &'a [u8], kind: &[u8; 4]) -> Option<&'a [u8]> {
    boxes(b).find(|(k, _)| k == kind).map(|(_, body)| body)
}

fn be32(b: &[u8], at: usize) -> Option<u32> {
    Some(u32::from_be_bytes(b.get(at..at + 4)?.try_into().ok()?))
}

fn be64(b: &[u8], at: usize) -> Option<u64> {
    Some(u64::from_be_bytes(b.get(at..at + 8)?.try_into().ok()?))
}

fn parse_moov(moov: &[u8]) -> Result<Vec<Source>, RemuxError> {
    let mvhd = child(moov, b"mvhd").ok_or(RemuxError::NotMine)?;
    let movie_scale = if mvhd.first() == Some(&1) { be32(mvhd, 20) } else { be32(mvhd, 12) }.unwrap_or(1000).max(1);
    // Defaults each track's fragments fall back on
    let mut trex = std::collections::HashMap::new();
    if let Some(mvex) = child(moov, b"mvex") {
        for (k, t) in boxes(mvex) {
            if &k == b"trex" {
                if let (Some(id), Some(d), Some(s), Some(f)) = (be32(t, 4), be32(t, 12), be32(t, 16), be32(t, 20)) {
                    trex.insert(id, (d, s, f));
                }
            }
        }
    }
    let mut out = vec![];
    for (k, trak) in boxes(moov) {
        if &k != b"trak" {
            continue;
        }
        let Some(tkhd) = child(trak, b"tkhd") else { continue };
        let v1 = tkhd.first() == Some(&1);
        let id = if v1 { be32(tkhd, 20) } else { be32(tkhd, 12) }.unwrap_or(0);
        let (width, height) = if v1 { (be32(tkhd, 88), be32(tkhd, 92)) } else { (be32(tkhd, 76), be32(tkhd, 80)) };
        let Some(mdia) = child(trak, b"mdia") else { continue };
        let Some(mdhd) = child(mdia, b"mdhd") else { continue };
        let timescale = if mdhd.first() == Some(&1) { be32(mdhd, 20) } else { be32(mdhd, 12) }.unwrap_or(0);
        let handler = child(mdia, b"hdlr").and_then(|h| h.get(8..12)).unwrap_or(&[]);
        let video = match handler {
            b"vide" => true,
            b"soun" => false,
            _ => continue, // subtitles, timed metadata
        };
        let stsd = child(mdia, b"minf").and_then(|m| child(m, b"stbl")).and_then(|s| child(s, b"stsd"));
        let Some(entry) = stsd.and_then(|s| s.get(8..)).and_then(|e| {
            let size = be32(e, 0)? as usize;
            e.get(..size)
        }) else {
            continue;
        };
        if matches!(&entry[4..8], b"encv" | b"enca") {
            return Err(RemuxError::Unsupported("encrypted".into()));
        }
        // Edit list: empty stretches, then where showing starts
        let mut media_time = 0i64;
        let mut empty = 0f64;
        if let Some(elst) = child(trak, b"edts").and_then(|e| child(e, b"elst")) {
            let v1 = elst.first() == Some(&1);
            let n = be32(elst, 4).unwrap_or(0) as usize;
            let step = if v1 { 20 } else { 12 };
            for i in 0..n {
                let at = 8 + i * step;
                let (dur, mt) = if v1 {
                    (be64(elst, at).unwrap_or(0), be64(elst, at + 8).unwrap_or(0) as i64)
                } else {
                    (be32(elst, at).unwrap_or(0) as u64, be32(elst, at + 4).unwrap_or(0) as i32 as i64)
                };
                if mt == -1 {
                    empty += dur as f64 / movie_scale as f64;
                } else {
                    media_time = mt;
                    break;
                }
            }
        }
        let (dd, ds, df) = trex.get(&id).copied().unwrap_or((0, 0, 0));
        if timescale == 0 {
            continue;
        }
        out.push(Source {
            id,
            video,
            timescale,
            entry: entry.to_vec(),
            width: width.unwrap_or(0),
            height: height.unwrap_or(0),
            media_time,
            empty,
            default_duration: dd,
            default_size: ds,
            default_flags: df,
        });
    }
    Ok(out)
}

/// One output track and where it came from.
struct Out {
    src: Source,
    index: usize,
    first: Option<u64>,
    reader: usize,
}

/// Writes the fragmented picture stream in `video` (with its own sound, or
/// the separate sound stream in `audio`) to `out` as an ordinary MP4.
pub fn fmp4_to_mp4<W: Write + Seek>(video: &Path, audio: Option<&Path>, out: W) -> Result<Stats, RemuxError> {
    let mut readers = vec![Reader::open(video)?];
    if let Some(a) = audio {
        readers.push(Reader::open(a)?);
    }
    let picture = readers[0].sources.iter().find(|s| s.video).cloned().ok_or(RemuxError::NoPicture)?;
    let sound = readers.last().unwrap().sources.iter().find(|s| !s.video).cloned();
    let mut w = Writer::new(BufWriter::with_capacity(1 << 20, out))?;
    let mut tracks = vec![];
    let mut outs = vec![];
    for (src, reader) in [(Some(picture), 0), (sound, readers.len() - 1)] {
        let Some(src) = src else { continue };
        let format = Format::Copied { entry: src.entry.clone(), video: src.video, width: src.width, height: src.height };
        tracks.push(Track::new(format, src.timescale, src.default_duration.max(1)));
        outs.push(Out { src, index: tracks.len() - 1, first: None, reader });
    }
    let wanted: Vec<Vec<u32>> = (0..readers.len())
        .map(|r| outs.iter().filter(|o| o.reader == r).map(|o| o.src.id).collect())
        .collect();
    let mut stats = Stats::default();
    let mut next: Vec<Option<Fragment>> = Vec::new();
    for (r, reader) in readers.iter_mut().enumerate() {
        next.push(reader.next(&wanted[r])?);
    }
    let seconds = |f: &Fragment, readers: &[Reader]| -> f64 {
        let src = readers.iter().find_map(|r| r.source(f.track)).unwrap();
        f.start as f64 / src.timescale as f64
    };
    // Fragments from both files, in time order
    loop {
        let pick = (0..next.len())
            .filter(|&r| next[r].is_some())
            .min_by(|&a, &b| seconds(next[a].as_ref().unwrap(), &readers).total_cmp(&seconds(next[b].as_ref().unwrap(), &readers)));
        let Some(r) = pick else { break };
        let frag = next[r].take().unwrap();
        next[r] = readers[r].next(&wanted[r])?;
        let o = outs.iter_mut().find(|o| o.reader == r && o.src.id == frag.track).unwrap();
        let first = *o.first.get_or_insert(frag.start);
        // The fragment's data, read in one go
        let lo = frag.samples.iter().map(|s| s.offset).min().unwrap();
        let hi = frag.samples.iter().map(|s| s.offset + s.size as u64).max().unwrap();
        let data = readers[r].read(lo, hi)?;
        let mut time = frag.start.saturating_sub(first);
        for s in &frag.samples {
            let t = &mut tracks[o.index];
            let mut at = time;
            if let Some(&last) = t.times.last() {
                at = at.max(last + 1);
            }
            t.times.push(at);
            t.offsets.push(s.composition);
            t.sizes.push(s.size);
            t.sync.push(s.sync);
            let from = (s.offset - lo) as usize;
            w.sample(&mut tracks, o.index, &data[from..from + s.size as usize])?;
            if o.src.video {
                stats.pictures += 1;
            } else {
                stats.sounds += 1;
            }
            time += s.duration as u64;
        }
    }
    let Some(v) = outs.iter().find(|o| o.src.video && o.first.is_some()) else { return Err(RemuxError::NoPicture) };
    // The film starts at the first picture shown; each track is placed against
    // that with its edit list, so the tracks keep their places exactly
    let shown_first = |o: &Out, t: &Track| -> f64 {
        let first_comp = t.times.iter().zip(&t.offsets).map(|(&d, &c)| d as i64 + c as i64).min().unwrap_or(0);
        (o.first.unwrap() as i64 + first_comp - o.src.media_time) as f64 / o.src.timescale as f64 + o.src.empty
    };
    let start = shown_first(v, &tracks[v.index]);
    for o in &outs {
        let Some(first) = o.first else { continue };
        let ts = o.src.timescale as f64;
        let x = (first as i64 - o.src.media_time) as f64 / ts + o.src.empty - start;
        let t = &mut tracks[o.index];
        if x >= 0.0 {
            t.delay_ms = (x * 1000.0).round() as u64;
            t.media_start = 0;
        } else {
            t.delay_ms = 0;
            t.media_start = (-x * ts).round() as u64;
        }
    }
    let used: Vec<usize> = outs.iter().filter(|o| o.first.is_some()).map(|o| o.index).collect();
    let tracks: Vec<Track> = tracks.into_iter().enumerate().filter(|(i, _)| used.contains(i)).map(|(_, t)| t).collect();
    w.finish(&tracks)?;
    Ok(stats)
}
