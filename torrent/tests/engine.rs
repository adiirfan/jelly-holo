//! Two engines on this machine: one shares a file, the other downloads it.

use std::{path::Path, time::{Duration, Instant}};

use jellytorrent::{Config, Engine, Stat};

fn config(folder: &Path, state: &Path) -> Config {
    Config {
        folder: folder.to_path_buf(),
        state_dir: state.to_path_buf(),
        port: 0,
        down_limit: 0,
        up_limit: 0,
        // Only the peer we name: nothing from the internet
        dht: false,
        trackers: false,
    }
}

/// A file of [len] bytes of a repeating but not uniform pattern.
fn make_file(dir: &Path, name: &str, len: usize) -> Vec<u8> {
    let data: Vec<u8> = (0..len).map(|i| ((i * 31 + i / 7) % 251) as u8).collect();
    std::fs::create_dir_all(dir).unwrap();
    std::fs::write(dir.join(name), &data).unwrap();
    data
}

struct Pair {
    _tmp: tempfile::TempDir,
    seeder: Engine,
    torrent: Vec<u8>,
    data: Vec<u8>,
    down_folder: std::path::PathBuf,
    down_state: std::path::PathBuf,
}

/// A seeder sharing one 3 MB file, and the .torrent for it.
fn seeded(name: &str) -> Pair {
    seeded_len(name, 3_000_000)
}

fn seeded_len(name: &str, len: usize) -> Pair {
    let tmp = tempfile::tempdir().unwrap();
    let share = tmp.path().join("share");
    let data = make_file(&share, name, len);
    let torrent = jellytorrent::create_torrent_for(&share.join(name)).unwrap();
    let seeder = Engine::start(config(&share, &tmp.path().join("seed-state"))).unwrap();
    let added = seeder.add_bytes(&torrent).unwrap();
    wait(&seeder, added.id, Duration::from_secs(20), |s| s.finished).expect("seeder never checked its file");
    Pair {
        down_folder: tmp.path().join("down"),
        down_state: tmp.path().join("down-state"),
        _tmp: tmp,
        seeder,
        torrent,
        data,
    }
}

fn wait(e: &Engine, id: usize, limit: Duration, done: impl Fn(&Stat) -> bool) -> Option<Stat> {
    let end = Instant::now() + limit;
    while Instant::now() < end {
        if let Some(s) = e.stats().into_iter().find(|s| s.id == id) {
            if done(&s) {
                return Some(s);
            }
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    None
}

#[test]
fn engine_downloads_from_a_peer() {
    let p = seeded("movie.bin");
    let b = Engine::start(config(&p.down_folder, &p.down_state)).unwrap();
    let peer = p.seeder.listen_port().expect("seeder has no port");
    let added = b.add_bytes_with_peer(&p.torrent, ("127.0.0.1", peer)).unwrap();
    let s = wait(&b, added.id, Duration::from_secs(30), |s| s.finished).expect("download never finished");
    assert_eq!(s.done, 3_000_000);
    assert_eq!(std::fs::read(p.down_folder.join("movie.bin")).unwrap(), p.data);
}

#[test]
fn engine_adding_the_same_torrent_twice_keeps_one() {
    let p = seeded("twice.bin");
    let first = p.seeder.add_bytes(&p.torrent).unwrap();
    let again = p.seeder.add_bytes(&p.torrent).unwrap();
    assert_eq!(first.id, again.id);
    assert_eq!(first.info_hash, again.info_hash);
    assert_eq!(p.seeder.stats().len(), 1);
}

#[test]
fn engine_resumes_after_restart_without_redownloading() {
    // The engine saves which pieces it has every 16 MB (and when done), so a
    // restart may lose up to that much, never the whole download.
    const SAVED_EVERY: u64 = 16 * 1024 * 1024;
    let p = seeded_len("resume.bin", 40_000_000);
    let peer = p.seeder.listen_port().unwrap();
    // Slow enough to stop partway
    let mut cfg = config(&p.down_folder, &p.down_state);
    cfg.down_limit = 10_000_000;
    let b = Engine::start(cfg).unwrap();
    let added = b.add_bytes_with_peer(&p.torrent, ("127.0.0.1", peer)).unwrap();
    let partway =
        wait(&b, added.id, Duration::from_secs(30), |s| s.done >= SAVED_EVERY + 4_000_000).expect("never got 20 MB");
    b.pause(added.id).unwrap();
    let before = b.stats()[0].done;
    assert!(before >= partway.done && before < 40_000_000, "finished before it could be stopped");
    b.stop();

    // Nobody to download from: whatever it has must come from what it saved
    p.seeder.stop();
    let b2 = Engine::start(config(&p.down_folder, &p.down_state)).unwrap();
    let s = wait(&b2, added.id, Duration::from_secs(20), |s| s.state != "initializing").expect("never came back");
    assert!(
        s.done >= SAVED_EVERY && s.done <= before,
        "had {before} bytes, came back with {} ({}, {:?})",
        s.done,
        s.state,
        s.error
    );
}

#[test]
fn engine_keeps_hostile_paths_inside_the_folder() {
    // A hand-made .torrent whose only file is called "../escape.txt"
    let tmp = tempfile::tempdir().unwrap();
    let folder = tmp.path().join("inside");
    std::fs::create_dir_all(&folder).unwrap();
    let torrent = jellytorrent::torrent_with_path(&["..", "escape.txt"], 16_384);
    let e = Engine::start(config(&folder, &tmp.path().join("state"))).unwrap();
    let _ = e.add_bytes(&torrent);
    std::thread::sleep(Duration::from_secs(2));
    assert!(!tmp.path().join("escape.txt").exists(), "a file was written outside the folder");
}

#[test]
fn engine_select_files_skips_unselected() {
    let tmp = tempfile::tempdir().unwrap();
    let share = tmp.path().join("share").join("pack");
    make_file(&share, "a.bin", 1_000_000);
    make_file(&share, "b.bin", 1_000_000);
    let torrent = jellytorrent::create_torrent_for(&share).unwrap();
    let seeder = Engine::start(config(&tmp.path().join("share"), &tmp.path().join("ss"))).unwrap();
    let a = seeder.add_bytes(&torrent).unwrap();
    wait(&seeder, a.id, Duration::from_secs(20), |s| s.finished).unwrap();

    let down = tmp.path().join("down");
    let b = Engine::start(config(&down, &tmp.path().join("ds"))).unwrap();
    let added = b.add_bytes_with_peer_paused(&torrent, ("127.0.0.1", seeder.listen_port().unwrap())).unwrap();
    let files = &b.stats()[0].files;
    let a_index = files.iter().position(|f| f.name.ends_with("a.bin")).unwrap();
    b.select_files(added.id, &[a_index]).unwrap();
    b.resume(added.id).unwrap();
    let s = wait(&b, added.id, Duration::from_secs(30), |s| s.finished).expect("never finished the chosen file");
    assert!(s.files[a_index].selected);
    assert_eq!(s.files[a_index].done, 1_000_000);
    let other = &s.files[1 - a_index];
    assert!(!other.selected);
    // Pieces shared with the chosen file may touch it; it must not be complete
    assert!(other.done < 1_000_000, "the skipped file was downloaded anyway");
}

#[test]
fn engine_stop_ends_a_magnet_still_being_looked_up() {
    let tmp = tempfile::tempdir().unwrap();
    let mut cfg = config(&tmp.path().join("down"), &tmp.path().join("state"));
    cfg.trackers = true;
    let e = std::sync::Arc::new(Engine::start(cfg).unwrap());
    let looking = e.clone();
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        // A tracker that never answers: the lookup can only end by being stopped
        let r = looking.add_magnet(
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&tr=udp%3A%2F%2F127.0.0.1%3A9%2Fannounce",
        );
        let _ = tx.send(r.is_err());
    });
    assert!(rx.recv_timeout(Duration::from_secs(2)).is_err(), "the lookup ended before the test could stop it");
    e.halt();
    let failed = rx.recv_timeout(Duration::from_secs(5)).expect("the lookup kept going after the engine stopped");
    assert!(failed, "a stopped engine still added the torrent");
}
