//! A small MP4 writer: samples are written as they come, the index (moov)
//! goes at the end. Every sample keeps its own time, so nothing is shifted.

use std::io::{self, Seek, SeekFrom, Write};

pub enum Format {
    Avc { width: u32, height: u32, sps: Vec<u8>, pps: Vec<u8> },
    Aac { asc: [u8; 2], channels: u16, rate: u32 },
    /// A sample entry copied as it is from another MP4, with that track's
    /// display size (16.16) when it's a picture
    Copied { entry: Vec<u8>, video: bool, width: u32, height: u32 },
}

impl Format {
    fn video(&self) -> bool {
        match self {
            Format::Avc { .. } => true,
            Format::Aac { .. } => false,
            Format::Copied { video, .. } => *video,
        }
    }

    /// Display size in 16.16 fixed point
    fn size(&self) -> (u32, u32) {
        match self {
            Format::Avc { width, height, .. } => (width << 16, height << 16),
            Format::Aac { .. } => (0, 0),
            Format::Copied { width, height, .. } => (*width, *height),
        }
    }
}

pub struct Track {
    pub format: Format,
    pub timescale: u32,
    /// Decode time of each sample, in `timescale` units, from 0, rising
    pub times: Vec<u64>,
    /// Shown time minus decode time (pictures with B-frames)
    pub offsets: Vec<i32>,
    pub sizes: Vec<u32>,
    pub sync: Vec<bool>,
    /// Movie time before this track begins (milliseconds)
    pub delay_ms: u64,
    /// Media time where showing starts (skips the B-frame lead-in)
    pub media_start: u64,
    /// The usual length of one sample, for the last one
    pub default_duration: u32,
    chunks: Vec<(u64, u32)>,
}

impl Track {
    pub fn new(format: Format, timescale: u32, default_duration: u32) -> Track {
        Track {
            format,
            timescale,
            times: vec![],
            offsets: vec![],
            sizes: vec![],
            sync: vec![],
            delay_ms: 0,
            media_start: 0,
            default_duration,
            chunks: vec![],
        }
    }

    fn durations(&self) -> Vec<u32> {
        let n = self.times.len();
        (0..n)
            .map(|i| {
                if i + 1 < n {
                    (self.times[i + 1] - self.times[i]) as u32
                } else if n > 1 {
                    (self.times[n - 1] - self.times[n - 2]) as u32
                } else {
                    self.default_duration
                }
            })
            .collect()
    }

    fn media_duration(&self) -> u64 {
        self.durations().iter().map(|&d| d as u64).sum()
    }

    /// How long the track shows, in milliseconds
    fn shown_ms(&self) -> u64 {
        self.media_duration().saturating_sub(self.media_start) * 1000 / self.timescale as u64
    }
}

pub struct Writer<W: Write + Seek> {
    w: W,
    pos: u64,
    mdat: u64,
    last_track: Option<usize>,
}

impl<W: Write + Seek> Writer<W> {
    pub fn new(mut w: W) -> io::Result<Writer<W>> {
        let mut ftyp = vec![];
        ftyp.extend(b"isom");
        ftyp.extend(512u32.to_be_bytes());
        for brand in [b"isom", b"iso2", b"avc1", b"mp41"] {
            ftyp.extend(brand);
        }
        let ftyp = bx(b"ftyp", &ftyp);
        w.write_all(&ftyp)?;
        let mdat = ftyp.len() as u64;
        // 64-bit size, filled in at the end
        w.write_all(&1u32.to_be_bytes())?;
        w.write_all(b"mdat")?;
        w.write_all(&0u64.to_be_bytes())?;
        Ok(Writer { w, pos: mdat + 16, mdat, last_track: None })
    }

    pub fn sample(&mut self, tracks: &mut [Track], t: usize, data: &[u8]) -> io::Result<()> {
        let track = &mut tracks[t];
        if self.last_track == Some(t) {
            if let Some(c) = track.chunks.last_mut() {
                c.1 += 1;
            }
        } else {
            track.chunks.push((self.pos, 1));
        }
        self.last_track = Some(t);
        self.w.write_all(data)?;
        self.pos += data.len() as u64;
        Ok(())
    }

    pub fn finish(mut self, tracks: &[Track]) -> io::Result<W> {
        let moov = moov(tracks);
        self.w.write_all(&moov)?;
        self.w.seek(SeekFrom::Start(self.mdat + 8))?;
        self.w.write_all(&(self.pos - self.mdat).to_be_bytes())?;
        self.w.seek(SeekFrom::End(0))?;
        self.w.flush()?;
        Ok(self.w)
    }
}

fn bx(kind: &[u8; 4], body: &[u8]) -> Vec<u8> {
    let mut b = Vec::with_capacity(body.len() + 8);
    b.extend(((body.len() + 8) as u32).to_be_bytes());
    b.extend(kind);
    b.extend(body);
    b
}

/// A box with version 0 and the given flags before its body.
fn full(kind: &[u8; 4], flags: u32, body: &[u8]) -> Vec<u8> {
    let mut b = flags.to_be_bytes().to_vec();
    b.extend(body);
    bx(kind, &b)
}

fn cat(parts: &[Vec<u8>]) -> Vec<u8> {
    parts.concat()
}

const MATRIX: [u32; 9] = [0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x4000_0000];

fn moov(tracks: &[Track]) -> Vec<u8> {
    let movie_ms = tracks.iter().map(|t| t.delay_ms + t.shown_ms()).max().unwrap_or(0);
    let mut mvhd = vec![];
    mvhd.extend(0u32.to_be_bytes()); // created
    mvhd.extend(0u32.to_be_bytes()); // modified
    mvhd.extend(1000u32.to_be_bytes());
    mvhd.extend((movie_ms as u32).to_be_bytes());
    mvhd.extend(0x10000u32.to_be_bytes()); // rate 1.0
    mvhd.extend(0x100u16.to_be_bytes()); // volume 1.0
    mvhd.extend([0u8; 10]);
    for m in MATRIX {
        mvhd.extend(m.to_be_bytes());
    }
    mvhd.extend([0u8; 24]);
    mvhd.extend((tracks.len() as u32 + 1).to_be_bytes());
    let mut parts = vec![full(b"mvhd", 0, &mvhd)];
    for (i, t) in tracks.iter().enumerate() {
        parts.push(trak(t, i as u32 + 1));
    }
    bx(b"moov", &cat(&parts))
}

fn trak(t: &Track, id: u32) -> Vec<u8> {
    let video = t.format.video();
    let (w, h) = t.format.size();
    let shown = t.shown_ms();
    let mut tkhd = vec![];
    tkhd.extend(0u32.to_be_bytes());
    tkhd.extend(0u32.to_be_bytes());
    tkhd.extend(id.to_be_bytes());
    tkhd.extend(0u32.to_be_bytes());
    tkhd.extend(((t.delay_ms + shown) as u32).to_be_bytes());
    tkhd.extend([0u8; 8]);
    tkhd.extend(0u16.to_be_bytes()); // layer
    tkhd.extend(0u16.to_be_bytes()); // alternate group
    tkhd.extend((if video { 0u16 } else { 0x100 }).to_be_bytes());
    tkhd.extend(0u16.to_be_bytes());
    for m in MATRIX {
        tkhd.extend(m.to_be_bytes());
    }
    tkhd.extend(w.to_be_bytes());
    tkhd.extend(h.to_be_bytes());

    // Edit list: an empty stretch for a late start, then the media from media_start
    let mut elst = vec![];
    let mut entries = 0u32;
    if t.delay_ms > 0 {
        entries += 1;
        elst.extend((t.delay_ms as u32).to_be_bytes());
        elst.extend((-1i32).to_be_bytes());
        elst.extend(0x10000u32.to_be_bytes());
    }
    entries += 1;
    elst.extend((shown as u32).to_be_bytes());
    elst.extend((t.media_start as i32).to_be_bytes());
    elst.extend(0x10000u32.to_be_bytes());
    let mut e = entries.to_be_bytes().to_vec();
    e.extend(elst);
    let edts = bx(b"edts", &full(b"elst", 0, &e));

    let mut mdhd = vec![];
    mdhd.extend(0u32.to_be_bytes());
    mdhd.extend(0u32.to_be_bytes());
    mdhd.extend(t.timescale.to_be_bytes());
    mdhd.extend((t.media_duration() as u32).to_be_bytes());
    mdhd.extend(0x55c4u16.to_be_bytes()); // "und"
    mdhd.extend(0u16.to_be_bytes());
    let mut hdlr = vec![0u8; 4];
    hdlr.extend(if video { b"vide" } else { b"soun" });
    hdlr.extend([0u8; 12]);
    hdlr.extend(if video { &b"VideoHandler\0"[..] } else { &b"SoundHandler\0"[..] });
    let header = if video {
        full(b"vmhd", 1, &[0u8; 8])
    } else {
        full(b"smhd", 0, &[0u8; 4])
    };
    let dinf = bx(b"dinf", &full(b"dref", 0, &cat(&[1u32.to_be_bytes().to_vec(), full(b"url ", 1, &[])])));
    let minf = bx(b"minf", &cat(&[header, dinf, stbl(t)]));
    let mdia = bx(b"mdia", &cat(&[full(b"mdhd", 0, &mdhd), full(b"hdlr", 0, &hdlr), minf]));
    bx(b"trak", &cat(&[full(b"tkhd", 3, &tkhd), edts, mdia]))
}

fn runs<T: PartialEq + Copy>(v: &[T]) -> Vec<(u32, T)> {
    let mut out: Vec<(u32, T)> = vec![];
    for &x in v {
        match out.last_mut() {
            Some((n, y)) if *y == x => *n += 1,
            _ => out.push((1, x)),
        }
    }
    out
}

fn table<T: PartialEq + Copy>(v: &[T], put: impl Fn(&mut Vec<u8>, T)) -> Vec<u8> {
    let r = runs(v);
    let mut b = (r.len() as u32).to_be_bytes().to_vec();
    for (n, x) in r {
        b.extend(n.to_be_bytes());
        put(&mut b, x);
    }
    b
}

fn stbl(t: &Track) -> Vec<u8> {
    let mut parts = vec![full(b"stsd", 0, &cat(&[1u32.to_be_bytes().to_vec(), entry(&t.format)]))];
    parts.push(full(b"stts", 0, &table(&t.durations(), |b, d| b.extend(d.to_be_bytes()))));
    if t.offsets.iter().any(|&o| o != 0) {
        // Version 1 allows offsets below zero
        let version = if t.offsets.iter().any(|&o| o < 0) { 1u32 << 24 } else { 0 };
        parts.push(full(b"ctts", version, &table(&t.offsets, |b, o| b.extend(o.to_be_bytes()))));
    }
    if t.format.video() && t.sync.iter().any(|s| !s) {
        let keys: Vec<u32> = t.sync.iter().enumerate().filter(|(_, &s)| s).map(|(i, _)| i as u32 + 1).collect();
        let mut b = (keys.len() as u32).to_be_bytes().to_vec();
        keys.iter().for_each(|k| b.extend(k.to_be_bytes()));
        parts.push(full(b"stss", 0, &b));
    }
    // Chunks with the same number of samples share one entry
    let mut stsc = vec![];
    let mut entries = 0u32;
    let mut last = None;
    for (i, &(_, n)) in t.chunks.iter().enumerate() {
        if last != Some(n) {
            stsc.extend((i as u32 + 1).to_be_bytes());
            stsc.extend(n.to_be_bytes());
            stsc.extend(1u32.to_be_bytes());
            entries += 1;
            last = Some(n);
        }
    }
    let mut b = entries.to_be_bytes().to_vec();
    b.extend(stsc);
    parts.push(full(b"stsc", 0, &b));
    let mut stsz = 0u32.to_be_bytes().to_vec();
    stsz.extend((t.sizes.len() as u32).to_be_bytes());
    t.sizes.iter().for_each(|s| stsz.extend(s.to_be_bytes()));
    parts.push(full(b"stsz", 0, &stsz));
    let mut co64 = (t.chunks.len() as u32).to_be_bytes().to_vec();
    t.chunks.iter().for_each(|(o, _)| co64.extend(o.to_be_bytes()));
    parts.push(full(b"co64", 0, &co64));
    bx(b"stbl", &cat(&parts))
}

fn entry(f: &Format) -> Vec<u8> {
    match f {
        Format::Avc { width, height, sps, pps } => {
            let mut b = vec![0u8; 6];
            b.extend(1u16.to_be_bytes()); // data reference
            b.extend([0u8; 16]);
            b.extend((*width as u16).to_be_bytes());
            b.extend((*height as u16).to_be_bytes());
            b.extend(0x0048_0000u32.to_be_bytes());
            b.extend(0x0048_0000u32.to_be_bytes());
            b.extend(0u32.to_be_bytes());
            b.extend(1u16.to_be_bytes()); // frames per sample
            b.extend([0u8; 32]); // compressor name
            b.extend(0x18u16.to_be_bytes());
            b.extend((-1i16).to_be_bytes());
            let mut avcc = vec![1, sps[1], sps[2], sps[3], 0xff, 0xe1];
            avcc.extend((sps.len() as u16).to_be_bytes());
            avcc.extend(sps);
            avcc.push(1);
            avcc.extend((pps.len() as u16).to_be_bytes());
            avcc.extend(pps);
            b.extend(bx(b"avcC", &avcc));
            bx(b"avc1", &b)
        }
        Format::Aac { asc, channels, rate } => {
            let mut b = vec![0u8; 6];
            b.extend(1u16.to_be_bytes());
            b.extend([0u8; 8]);
            b.extend(channels.to_be_bytes());
            b.extend(16u16.to_be_bytes());
            b.extend([0u8; 4]);
            b.extend(((*rate).min(0xffff) << 16).to_be_bytes());
            b.extend(esds(asc));
            bx(b"mp4a", &b)
        }
        Format::Copied { entry, .. } => entry.clone(),
    }
}

fn descriptor(tag: u8, body: &[u8]) -> Vec<u8> {
    let n = body.len() as u32;
    let mut b = vec![tag, 0x80 | (n >> 21) as u8 & 0x7f, 0x80 | (n >> 14) as u8 & 0x7f, 0x80 | (n >> 7) as u8 & 0x7f, n as u8 & 0x7f];
    b.extend(body);
    b
}

fn esds(asc: &[u8; 2]) -> Vec<u8> {
    let mut dc = vec![0x40, 0x15, 0, 0, 0];
    dc.extend(0u32.to_be_bytes()); // max bit rate
    dc.extend(0u32.to_be_bytes()); // average bit rate
    dc.extend(descriptor(5, asc));
    let mut es = vec![0, 0, 0]; // id, flags
    es.extend(descriptor(4, &dc));
    es.extend(descriptor(6, &[2]));
    full(b"esds", 0, &descriptor(3, &es))
}
