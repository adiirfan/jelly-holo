//! Joins a downloaded stream (HLS pieces in MPEG-TS, H.264 picture and AAC
//! sound) into one MP4 without re-encoding. Every picture and sound frame
//! keeps its own time from the stream, so the two stay in step exactly.
//!
//! Anything else (H.265, other sound formats, fragmented MP4 pieces) is
//! refused with [`RemuxError::Unsupported`] and left to Android's tools.

pub mod aac;
pub mod h264;
pub mod mp4;
pub mod ts;

use std::fmt;
use std::io::{self, BufWriter, Seek, Write};
use std::path::Path;

use mp4::{Format, Track, Writer};
use ts::{Demux, Kind, Pes};

#[derive(Debug)]
pub enum RemuxError {
    NotTs,
    Unsupported(String),
    NoPicture,
    Io(io::Error),
}

impl From<io::Error> for RemuxError {
    fn from(e: io::Error) -> Self {
        RemuxError::Io(e)
    }
}

impl fmt::Display for RemuxError {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            RemuxError::NotTs => write!(f, "not an MPEG-TS stream"),
            RemuxError::Unsupported(what) => write!(f, "unsupported: {what}"),
            RemuxError::NoPicture => write!(f, "no picture found"),
            RemuxError::Io(e) => write!(f, "{e}"),
        }
    }
}

#[derive(Debug, Default)]
pub struct Stats {
    pub pictures: usize,
    pub sounds: usize,
}

/// The width and height an H.264 SPS unit (header byte included) describes.
pub fn h264_picture_size(sps: &[u8]) -> Option<(u32, u32)> {
    h264::picture_size(sps)
}

/// Writes the stream in `video` (with its own sound, or the separate sound
/// stream in `audio`) to `out` as an MP4.
pub fn ts_to_mp4<W: Write + Seek>(video: &Path, audio: Option<&Path>, out: W) -> Result<Stats, RemuxError> {
    if !ts::looks_like_ts(video)? || audio.map(ts::looks_like_ts).transpose()? == Some(false) {
        return Err(RemuxError::NotTs);
    }
    let mut j = Joiner::new(Writer::new(BufWriter::with_capacity(1 << 20, out))?);
    let mut vd = Demux::open(video)?;
    let mut ad = audio.map(Demux::open).transpose()?;
    let separate = ad.is_some();
    let mut vclock = Clock::default();
    let mut aclock = Clock::default();
    let mut next_v = vd.next()?;
    let mut next_a = match ad.as_mut() {
        Some(d) => d.next()?,
        None => None,
    };
    // Both streams are read in time order, so the file is interleaved
    loop {
        let take_video = match (&next_v, &next_a) {
            (None, None) => break,
            (Some(_), None) => true,
            (None, Some(_)) => false,
            (Some(v), Some(a)) => vclock.peek(v.dts.or(v.pts)) <= aclock.peek(a.pts),
        };
        if take_video {
            let p = next_v.take().unwrap();
            next_v = vd.next()?;
            match p.kind {
                Kind::H264 => j.picture(&mut vclock, p)?,
                Kind::Aac if !separate => j.sound(&mut vclock, p)?,
                Kind::OtherPicture(t) => return Err(RemuxError::Unsupported(format!("picture type {t:#x}"))),
                Kind::OtherSound(t) if !separate => return Err(RemuxError::Unsupported(format!("sound type {t:#x}"))),
                _ => {}
            }
        } else {
            let p = next_a.take().unwrap();
            next_a = ad.as_mut().unwrap().next()?;
            match p.kind {
                Kind::Aac => j.sound(&mut aclock, p)?,
                Kind::OtherSound(t) => return Err(RemuxError::Unsupported(format!("sound type {t:#x}"))),
                _ => {}
            }
        }
    }
    j.finish()
}

/// Follows a 33-bit 90 kHz clock across its roll-over (about every 26.5 hours).
#[derive(Default)]
struct Clock {
    last: Option<i64>,
    offset: i64,
}

const WRAP: i64 = 1 << 33;

impl Clock {
    fn unwrap(&mut self, t: u64) -> i64 {
        let v = self.peek(Some(t));
        let shift = v - (t as i64 + self.offset);
        self.offset += shift;
        self.last = Some(v);
        v
    }

    fn peek(&self, t: Option<u64>) -> i64 {
        let Some(t) = t else { return self.last.unwrap_or(0) };
        let mut v = t as i64 + self.offset;
        if let Some(l) = self.last {
            if v < l - WRAP / 2 {
                v += WRAP;
            } else if v > l + WRAP / 2 {
                v -= WRAP;
            }
        }
        v
    }
}

struct Joiner<W: Write + Seek> {
    w: Writer<W>,
    tracks: Vec<Track>,
    video: Option<usize>,
    audio: Option<usize>,
    sps: Option<Vec<u8>>,
    pps: Option<Vec<u8>>,
    /// Shown time of the first picture: time 0 of the file (90 kHz)
    start: Option<i64>,
    first_dts: i64,
    /// Sound that came before the first picture, held until it's known
    waiting: Vec<(i64, Vec<u8>)>,
    leftover: Vec<u8>,
    /// Sound frames are timed by counting 1024 samples from an anchor
    anchor: Option<i64>,
    counted: i64,
    rate: i64,
    stats: Stats,
}

impl<W: Write + Seek> Joiner<W> {
    fn new(w: Writer<W>) -> Self {
        Joiner {
            w,
            tracks: vec![],
            video: None,
            audio: None,
            sps: None,
            pps: None,
            start: None,
            first_dts: 0,
            waiting: vec![],
            leftover: vec![],
            anchor: None,
            counted: 0,
            rate: 0,
            stats: Stats::default(),
        }
    }

    fn picture(&mut self, clock: &mut Clock, p: Pes) -> Result<(), RemuxError> {
        let Some(pts) = p.pts else { return Ok(()) };
        let pts = clock.unwrap(pts);
        let dts = p.dts.map(|d| clock.unwrap(d)).unwrap_or(pts);
        let mut sample = Vec::with_capacity(p.data.len() + 16);
        let mut key = false;
        let mut slice = false;
        for u in h264::units(&p.data) {
            match h264::unit_type(u) {
                h264::SPS => {
                    if self.sps.is_none() {
                        self.sps = Some(u.to_vec());
                    }
                }
                h264::PPS => {
                    if self.pps.is_none() {
                        self.pps = Some(u.to_vec());
                    }
                }
                h264::AUD | h264::FILLER => {}
                t => {
                    key |= t == h264::IDR;
                    slice |= (1..=5).contains(&t);
                    sample.extend((u.len() as u32).to_be_bytes());
                    sample.extend(u);
                }
            }
        }
        if !slice {
            return Ok(());
        }
        let start = match self.start {
            Some(s) => s,
            None => {
                // The file starts at the first whole picture
                let (Some(sps), Some(pps)) = (&self.sps, &self.pps) else { return Ok(()) };
                if !key {
                    return Ok(());
                }
                let (width, height) = h264::picture_size(sps)
                    .ok_or_else(|| RemuxError::Unsupported("unreadable picture settings".into()))?;
                self.tracks.push(Track::new(Format::Avc { width, height, sps: sps.clone(), pps: pps.clone() }, 90_000, 3600));
                self.video = Some(self.tracks.len() - 1);
                self.start = Some(pts);
                self.first_dts = dts;
                for (t, data) in std::mem::take(&mut self.waiting) {
                    self.sound_at(t, data)?;
                }
                pts
            }
        };
        if pts < start {
            return Ok(()); // shown before the first picture: belongs to what came before
        }
        let v = self.video.unwrap();
        let track = &mut self.tracks[v];
        let mut time = (dts - self.first_dts).max(0) as u64;
        if let Some(&last) = track.times.last() {
            time = time.max(last + 1);
        }
        let shown = (pts - self.first_dts).max(0) as u64;
        track.times.push(time);
        track.offsets.push(shown.saturating_sub(time) as u32);
        track.sizes.push(sample.len() as u32);
        track.sync.push(key);
        self.w.sample(&mut self.tracks, v, &sample)?;
        self.stats.pictures += 1;
        Ok(())
    }

    fn sound(&mut self, clock: &mut Clock, p: Pes) -> Result<(), RemuxError> {
        let t = match p.pts {
            Some(t) => clock.unwrap(t),
            None => i64::MIN,
        };
        if self.start.is_none() {
            self.waiting.push((t, p.data));
            return Ok(());
        }
        self.sound_at(t, p.data)
    }

    fn sound_at(&mut self, pts: i64, data: Vec<u8>) -> Result<(), RemuxError> {
        let start = self.start.unwrap();
        let fresh = self.leftover.is_empty();
        self.leftover.extend(data);
        let buf = std::mem::take(&mut self.leftover);
        let (frames, used) = aac::frames(&buf);
        for (i, (config, range)) in frames.into_iter().enumerate() {
            if self.audio.is_none() {
                self.rate = config.rate() as i64;
                let format = Format::Aac { asc: config.asc(), channels: config.channels.max(1) as u16, rate: config.rate() };
                self.tracks.push(Track::new(format, config.rate(), 1024));
                self.audio = Some(self.tracks.len() - 1);
            }
            // Count on from the last frame; a jump ahead of over 0.1 s in the
            // stream's own time is a real gap, and counting starts again there
            if i == 0 && fresh && pts != i64::MIN {
                let at = (pts - start) * self.rate / 90_000;
                let counted = self.anchor.map(|a| a + self.counted * 1024);
                if counted.is_none_or(|c| at - c > self.rate / 10) {
                    self.anchor = Some(at);
                    self.counted = 0;
                }
            }
            let Some(anchor) = self.anchor else { continue };
            let time = anchor + self.counted * 1024;
            self.counted += 1;
            if time < -(self.rate / 2000) {
                continue; // before the first picture
            }
            let a = self.audio.unwrap();
            let track = &mut self.tracks[a];
            let mut t = time.max(0) as u64;
            if let Some(&last) = track.times.last() {
                t = t.max(last + 1);
            }
            track.times.push(t);
            track.offsets.push(0);
            track.sizes.push(range.len() as u32);
            track.sync.push(true);
            self.w.sample(&mut self.tracks, a, &buf[range])?;
            self.stats.sounds += 1;
        }
        self.leftover = buf[used..].to_vec();
        Ok(())
    }

    fn finish(mut self) -> Result<Stats, RemuxError> {
        let Some(v) = self.video else { return Err(RemuxError::NoPicture) };
        let start = self.start.unwrap();
        self.tracks[v].media_start = (start - self.first_dts).max(0) as u64;
        if let Some(a) = self.audio {
            let track = &mut self.tracks[a];
            if let Some(&first) = track.times.first() {
                // The sound's own time starts at 0; its start in the film is a delay
                track.times.iter_mut().for_each(|t| *t -= first);
                track.delay_ms = (first * 1000 + track.timescale as u64 / 2) / track.timescale as u64;
            } else {
                self.tracks.remove(a);
            }
        }
        self.w.finish(&self.tracks)?;
        Ok(self.stats)
    }
}
