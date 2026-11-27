//! AAC sound as sent in a stream: frames each with a 7- or 9-byte ADTS
//! header. An MP4 holds the bare frames and the settings once.

pub const RATES: [u32; 13] = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Config {
    /// AAC object type (2 = LC)
    pub object: u8,
    pub rate_index: u8,
    pub channels: u8,
}

impl Config {
    pub fn rate(&self) -> u32 {
        RATES.get(self.rate_index as usize).copied().unwrap_or(44100)
    }

    /// The AudioSpecificConfig an MP4 carries.
    pub fn asc(&self) -> [u8; 2] {
        [(self.object << 3) | (self.rate_index >> 1), ((self.rate_index & 1) << 7) | (self.channels << 3)]
    }
}

/// Splits whole ADTS frames off the front of `buf`; what's left is the start
/// of a frame that continues in the next packet. Bytes that aren't a frame
/// header are skipped.
pub fn frames(buf: &[u8]) -> (Vec<(Config, std::ops::Range<usize>)>, usize) {
    let mut out = vec![];
    let mut at = 0;
    while at + 7 <= buf.len() {
        let b = &buf[at..];
        if b[0] != 0xff || b[1] & 0xf6 != 0xf0 {
            at += 1;
            continue;
        }
        let header = if b[1] & 1 == 1 { 7 } else { 9 };
        let length = (((b[3] & 3) as usize) << 11) | ((b[4] as usize) << 3) | ((b[5] as usize) >> 5);
        let rate_index = (b[2] >> 2) & 0x0f;
        if length <= header || rate_index as usize >= RATES.len() {
            at += 1;
            continue;
        }
        if at + length > buf.len() {
            break;
        }
        let config = Config { object: ((b[2] >> 6) & 3) + 1, rate_index, channels: ((b[2] & 1) << 2) | (b[3] >> 6) };
        out.push((config, at + header..at + length));
        at += length;
    }
    (out, at)
}
