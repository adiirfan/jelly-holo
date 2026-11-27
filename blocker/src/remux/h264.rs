//! H.264 pictures as sent in a stream (start codes before each unit), turned
//! into what an MP4 holds (a length before each unit, settings kept apart).

/// The units in `data`, split at start codes (00 00 01 / 00 00 00 01).
pub fn units(data: &[u8]) -> Vec<&[u8]> {
    let mut out = vec![];
    let mut start: Option<usize> = None;
    let mut i = 0;
    while i + 3 <= data.len() {
        if data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 {
            if let Some(s) = start {
                out.push(trim(&data[s..i]));
            }
            i += 3;
            start = Some(i);
        } else {
            i += 1;
        }
    }
    if let Some(s) = start {
        out.push(trim(&data[s..]));
    }
    out.retain(|u| !u.is_empty());
    out
}

/// Zero bytes at the end belong to the next start code.
fn trim(u: &[u8]) -> &[u8] {
    let mut end = u.len();
    while end > 0 && u[end - 1] == 0 {
        end -= 1;
    }
    &u[..end]
}

pub fn unit_type(u: &[u8]) -> u8 {
    u[0] & 0x1f
}

pub const IDR: u8 = 5;
pub const SPS: u8 = 7;
pub const PPS: u8 = 8;
pub const AUD: u8 = 9;
pub const FILLER: u8 = 12;

/// The picture's width and height from its SPS unit (header byte included).
pub fn picture_size(sps: &[u8]) -> Option<(u32, u32)> {
    let rbsp = unescape(sps.get(1..)?);
    let profile = *rbsp.first()?;
    let mut r = Bits::new(rbsp.get(3..)?);
    r.ue()?; // seq_parameter_set_id
    let mut chroma = 1;
    let mut separate_planes = false;
    if matches!(profile, 100 | 110 | 122 | 244 | 44 | 83 | 86 | 118 | 128 | 138 | 139 | 134 | 135) {
        chroma = r.ue()?;
        if chroma == 3 {
            separate_planes = r.bit()? == 1;
        }
        r.ue()?; // bit depth, picture
        r.ue()?; // bit depth, colour
        r.bit()?; // qpprime_y_zero_transform_bypass
        if r.bit()? == 1 {
            for i in 0..(if chroma == 3 { 12 } else { 8 }) {
                if r.bit()? == 1 {
                    skip_scaling_list(&mut r, if i < 6 { 16 } else { 64 })?;
                }
            }
        }
    }
    r.ue()?; // log2_max_frame_num
    match r.ue()? {
        0 => {
            r.ue()?;
        }
        1 => {
            r.bit()?;
            r.se()?;
            r.se()?;
            for _ in 0..r.ue()? {
                r.se()?;
            }
        }
        _ => {}
    }
    r.ue()?; // max_num_ref_frames
    r.bit()?; // gaps_in_frame_num_allowed
    let w_mbs = r.ue()? + 1;
    let h_units = r.ue()? + 1;
    let frame_only = r.bit()?;
    if frame_only == 0 {
        r.bit()?; // mb_adaptive_frame_field
    }
    r.bit()?; // direct_8x8_inference
    let mut width = w_mbs * 16;
    let mut height = (2 - frame_only) * h_units * 16;
    if r.bit()? == 1 {
        let (left, right, top, bottom) = (r.ue()?, r.ue()?, r.ue()?, r.ue()?);
        let array_type = if separate_planes { 0 } else { chroma };
        let (ux, uy) = match array_type {
            0 => (1, 2 - frame_only),
            1 => (2, 2 * (2 - frame_only)),
            2 => (2, 2 - frame_only),
            _ => (1, 2 - frame_only),
        };
        width = width.checked_sub((left + right) * ux)?;
        height = height.checked_sub((top + bottom) * uy)?;
    }
    Some((width, height))
}

fn skip_scaling_list(r: &mut Bits, size: usize) -> Option<()> {
    let (mut last, mut next) = (8i32, 8i32);
    for _ in 0..size {
        if next != 0 {
            next = (last + r.se()? + 256) % 256;
        }
        if next != 0 {
            last = next;
        }
    }
    Some(())
}

/// Removes the 03 bytes inserted after 00 00 so the data can't look like a start code.
fn unescape(b: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(b.len());
    let mut zeros = 0;
    for &x in b {
        if zeros >= 2 && x == 3 {
            zeros = 0;
            continue;
        }
        zeros = if x == 0 { zeros + 1 } else { 0 };
        out.push(x);
    }
    out
}

struct Bits<'a> {
    b: &'a [u8],
    at: usize,
}

impl<'a> Bits<'a> {
    fn new(b: &'a [u8]) -> Self {
        Bits { b, at: 0 }
    }

    fn bit(&mut self) -> Option<u32> {
        let byte = *self.b.get(self.at / 8)?;
        let v = (byte >> (7 - self.at % 8)) & 1;
        self.at += 1;
        Some(v as u32)
    }

    /// Exp-Golomb, unsigned
    fn ue(&mut self) -> Option<u32> {
        let mut zeros = 0;
        while self.bit()? == 0 {
            zeros += 1;
            if zeros > 31 {
                return None;
            }
        }
        let mut v: u64 = 1;
        for _ in 0..zeros {
            v = (v << 1) | self.bit()? as u64;
        }
        u32::try_from(v - 1).ok()
    }

    /// Exp-Golomb, signed
    fn se(&mut self) -> Option<i32> {
        let k = self.ue()? as i64;
        Some(if k % 2 == 1 { ((k + 1) / 2) as i32 } else { -(k / 2) as i32 })
    }
}
