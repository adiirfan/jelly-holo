//! Joins a TS stream into an MP4 from the command line, for checking files by hand.
//! cargo run --release --example remux -- video.ts [sound.ts] out.mp4
use std::fs::File;
use std::path::Path;
use std::time::Instant;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (video, audio, out) = match args.as_slice() {
        [v, o] => (v, None, o),
        [v, a, o] => (v, Some(a), o),
        _ => {
            eprintln!("usage: remux video.ts [sound.ts] out.mp4");
            std::process::exit(2);
        }
    };
    let t = Instant::now();
    let f = File::create(out).expect("can't create the output");
    match jellyblock::remux::ts_to_mp4(Path::new(video), audio.map(Path::new), f) {
        Ok(s) => println!("{} pictures, {} sound frames in {:.2} s", s.pictures, s.sounds, t.elapsed().as_secs_f64()),
        Err(e) => {
            eprintln!("failed: {e}");
            std::process::exit(1);
        }
    }
}
