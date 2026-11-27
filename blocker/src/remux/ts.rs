//! Reads an MPEG-TS stream (the pieces of an HLS stream joined end to end)
//! into whole PES packets: one picture or a run of sound frames each, with
//! their times.
//!
//! The packet counters are ignored on purpose: many servers start them again
//! in every piece, and treating that as damage is what loses sound at joins.

use std::collections::HashMap;
use std::fs::File;
use std::io::{self, BufReader, Read};
use std::path::Path;

pub const PACKET: usize = 188;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    H264,
    /// A picture format this joiner doesn't write (H.265, MPEG-2, ...)
    OtherPicture(u8),
    /// AAC in ADTS frames
    Aac,
    /// A sound format this joiner doesn't write (AC-3, MP3, LATM, ...)
    OtherSound(u8),
}

pub struct Pes {
    pub kind: Kind,
    /// 90 kHz, as sent: 33 bits that roll over
    pub pts: Option<u64>,
    pub dts: Option<u64>,
    pub data: Vec<u8>,
}

pub struct Demux {
    r: BufReader<File>,
    pmt_pid: Option<u16>,
    streams: HashMap<u16, Kind>,
    partial: HashMap<u16, Vec<u8>>,
    /// Finished packets, oldest first
    ready: std::collections::VecDeque<Pes>,
    done: bool,
}

/// True when [file] starts like a TS stream.
pub fn looks_like_ts(file: &Path) -> io::Result<bool> {
    let mut head = [0u8; PACKET * 2 + 1];
    let mut f = File::open(file)?;
    let mut n = 0;
    while n < head.len() {
        let m = f.read(&mut head[n..])?;
        if m == 0 {
            break;
        }
        n += m;
    }
    Ok(n >= PACKET && head[0] == 0x47 && (n < PACKET * 2 || head[PACKET] == 0x47))
}

impl Demux {
    pub fn open(file: &Path) -> io::Result<Demux> {
        Ok(Demux {
            r: BufReader::with_capacity(1 << 20, File::open(file)?),
            pmt_pid: None,
            streams: HashMap::new(),
            partial: HashMap::new(),
            ready: Default::default(),
            done: false,
        })
    }

    /// The streams the program table lists (empty until it has been read).
    pub fn streams(&self) -> impl Iterator<Item = Kind> + '_ {
        self.streams.values().copied()
    }

    /// The next whole packet, or None at the end.
    pub fn next(&mut self) -> io::Result<Option<Pes>> {
        loop {
            if let Some(p) = self.ready.pop_front() {
                return Ok(Some(p));
            }
            if self.done {
                return Ok(None);
            }
            let mut pkt = [0u8; PACKET];
            if !self.read_packet(&mut pkt)? {
                self.done = true;
                // What's still being gathered is complete at the end of the stream
                let mut pids: Vec<u16> = self.partial.keys().copied().collect();
                pids.sort_unstable();
                for pid in pids {
                    self.finish(pid);
                }
                continue;
            }
            self.packet(&pkt);
        }
    }

    /// Reads one packet, finding the sync byte again after damage. False at the end.
    fn read_packet(&mut self, pkt: &mut [u8; PACKET]) -> io::Result<bool> {
        if !fill(&mut self.r, pkt)? {
            return Ok(false);
        }
        while pkt[0] != 0x47 {
            pkt.copy_within(1.., 0);
            if !fill(&mut self.r, &mut pkt[PACKET - 1..])? {
                return Ok(false);
            }
        }
        Ok(true)
    }

    fn packet(&mut self, p: &[u8; PACKET]) {
        if p[1] & 0x80 != 0 {
            return; // marked as damaged in transit
        }
        let start = p[1] & 0x40 != 0;
        let pid = (((p[1] & 0x1f) as u16) << 8) | p[2] as u16;
        let afc = (p[3] >> 4) & 3;
        if afc & 1 == 0 {
            return; // no payload
        }
        let mut at = 4;
        if afc & 2 != 0 {
            at += 1 + p[4] as usize;
        }
        if at >= PACKET {
            return;
        }
        let payload = &p[at..];
        if pid == 0 {
            if start {
                self.pat(payload);
            }
        } else if Some(pid) == self.pmt_pid {
            if start {
                self.pmt(payload);
            }
        } else if self.streams.contains_key(&pid) {
            if start {
                self.finish(pid);
                self.partial.insert(pid, payload.to_vec());
            } else if let Some(buf) = self.partial.get_mut(&pid) {
                buf.extend_from_slice(payload);
            }
        }
    }

    fn section(payload: &[u8]) -> Option<&[u8]> {
        let pointer = *payload.first()? as usize;
        let s = payload.get(1 + pointer..)?;
        let len = ((((*s.get(1)? as usize) & 0x0f) << 8) | *s.get(2)? as usize) + 3;
        // Without the 4-byte check at the end
        s.get(..len.checked_sub(4)?)
    }

    fn pat(&mut self, payload: &[u8]) {
        let Some(s) = Self::section(payload) else { return };
        if s.first() != Some(&0) {
            return;
        }
        for e in s.get(8..).unwrap_or(&[]).chunks_exact(4) {
            let program = ((e[0] as u16) << 8) | e[1] as u16;
            if program != 0 {
                self.pmt_pid = Some((((e[2] & 0x1f) as u16) << 8) | e[3] as u16);
                return;
            }
        }
    }

    fn pmt(&mut self, payload: &[u8]) {
        let Some(s) = Self::section(payload) else { return };
        if s.first() != Some(&2) || s.len() < 12 {
            return;
        }
        let info = ((((s[10] & 0x0f) as usize) << 8) | s[11] as usize) + 12;
        let mut at = info;
        while at + 5 <= s.len() {
            let ty = s[at];
            let pid = (((s[at + 1] & 0x1f) as u16) << 8) | s[at + 2] as u16;
            let es_info = (((s[at + 3] & 0x0f) as usize) << 8) | s[at + 4] as usize;
            let kind = match ty {
                0x1b => Some(Kind::H264),
                0x01 | 0x02 | 0x10 | 0x24 | 0x42 | 0xea => Some(Kind::OtherPicture(ty)),
                0x0f => Some(Kind::Aac),
                0x03 | 0x04 | 0x11 | 0x81 | 0x87 => Some(Kind::OtherSound(ty)),
                _ => None, // timed metadata and the like
            };
            if let Some(k) = kind {
                self.streams.entry(pid).or_insert(k);
            }
            at += 5 + es_info;
        }
    }

    fn finish(&mut self, pid: u16) {
        let Some(buf) = self.partial.remove(&pid) else { return };
        let Some(kind) = self.streams.get(&pid).copied() else { return };
        if let Some(pes) = parse_pes(kind, buf) {
            self.ready.push_back(pes);
        }
    }
}

fn fill(r: &mut impl Read, buf: &mut [u8]) -> io::Result<bool> {
    let mut n = 0;
    while n < buf.len() {
        let m = r.read(&mut buf[n..])?;
        if m == 0 {
            return Ok(false);
        }
        n += m;
    }
    Ok(true)
}

fn parse_pes(kind: Kind, mut b: Vec<u8>) -> Option<Pes> {
    if b.len() < 9 || b[0..3] != [0, 0, 1] {
        return None;
    }
    let length = ((b[4] as usize) << 8) | b[5] as usize;
    let flags = b[7] >> 6;
    let header = 9 + b[8] as usize;
    if b.len() < header {
        return None;
    }
    let pts = if flags & 2 != 0 && b.len() >= 14 { Some(time(&b[9..14])) } else { None };
    let dts = if flags == 3 && b.len() >= 19 { Some(time(&b[14..19])) } else { None };
    // A stated length (not 0, which means "until the next packet") trims stuffing
    if length > 0 && 6 + length >= header && 6 + length < b.len() {
        b.truncate(6 + length);
    }
    b.drain(..header);
    Some(Pes { kind, pts, dts, data: b })
}

fn time(b: &[u8]) -> u64 {
    (((b[0] as u64 >> 1) & 7) << 30)
        | ((b[1] as u64) << 22)
        | ((b[2] as u64 >> 1) << 15)
        | ((b[3] as u64) << 7)
        | (b[4] as u64 >> 1)
}
