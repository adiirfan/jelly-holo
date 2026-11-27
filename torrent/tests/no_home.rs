//! Android apps have no home folder: the engine must keep everything it
//! saves in the folders it is given. Its own test binary, since it changes
//! the environment for the whole process.

use jellytorrent::{Config, Engine};

#[test]
fn engine_saves_nothing_outside_its_folders() {
    let home = tempfile::tempdir().unwrap();
    // Safety: the only test in this binary, before the engine starts any thread
    unsafe {
        for v in ["HOME", "XDG_DATA_HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME"] {
            std::env::set_var(v, home.path());
        }
    }
    let tmp = tempfile::tempdir().unwrap();
    let e = Engine::start(Config::new(tmp.path().join("down"), tmp.path().join("state"))).unwrap();
    e.stop();
    let written: Vec<_> = walk(home.path());
    assert!(written.is_empty(), "the engine wrote into the home folder: {written:?}");
}

fn walk(dir: &std::path::Path) -> Vec<std::path::PathBuf> {
    std::fs::read_dir(dir)
        .unwrap()
        .flat_map(|e| {
            let p = e.unwrap().path();
            if p.is_dir() { [vec![p.clone()], walk(&p)].concat() } else { vec![p] }
        })
        .collect()
}
