//! The browser's torrent engine: librqbit behind a small blocking API that
//! the Android service drives over JNI (see `jni.rs`).
//!
//! It never ties itself to a network: on Android the whole torrent process
//! is tied to the VPN by the service (`bindProcessToNetwork`), because the
//! phone's kernel refuses SO_BINDTODEVICE to normal apps.

use std::{
    collections::HashSet,
    net::{Ipv6Addr, SocketAddr, ToSocketAddrs},
    num::NonZeroU32,
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};

use anyhow::Context;
use librqbit::{
    AddTorrent, AddTorrentOptions, AddTorrentResponse, CreateTorrentOptions, DhtSessionConfig, ListenerMode,
    ListenerOptions, ManagedTorrent, Session, SessionOptions, SessionPersistenceConfig, TorrentStatsState,
    api::TorrentIdOrHash, dht::DhtPersistenceConfig, limits::LimitsConfig, spawn_utils::BlockingSpawner,
};
use serde::Serialize;

#[cfg(target_os = "android")]
mod jni;

/// How the engine runs.
#[derive(Debug, Clone)]
pub struct Config {
    /// Where downloads are saved.
    pub folder: PathBuf,
    /// Where the engine remembers its torrents across restarts.
    pub state_dir: PathBuf,
    /// The port to listen on; 0 = any free one.
    pub port: u16,
    /// Bytes per second; 0 = no limit.
    pub down_limit: u32,
    pub up_limit: u32,
    /// Find people through the DHT network.
    pub dht: bool,
    /// Ask the torrents' trackers for people.
    pub trackers: bool,
}

impl Config {
    pub fn new(folder: PathBuf, state_dir: PathBuf) -> Self {
        Self { folder, state_dir, port: 0, down_limit: 0, up_limit: 0, dht: true, trackers: true }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct Added {
    pub id: usize,
    pub info_hash: String,
    pub name: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct FileStat {
    pub name: String,
    pub size: u64,
    pub done: u64,
    pub selected: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct Stat {
    pub id: usize,
    pub info_hash: String,
    pub name: String,
    /// "initializing", "live", "paused" or "error".
    pub state: String,
    pub done: u64,
    pub total: u64,
    pub uploaded: u64,
    pub down_bps: u64,
    pub up_bps: u64,
    pub peers: u32,
    pub finished: bool,
    pub error: Option<String>,
    pub files: Vec<FileStat>,
}

pub struct Engine {
    rt: tokio::runtime::Runtime,
    session: Arc<Session>,
}

/// How long choosing files waits for a new torrent's check of the disk.
const INIT_WAIT: Duration = Duration::from_secs(120);

fn limit(bps: u32) -> Option<NonZeroU32> {
    NonZeroU32::new(bps)
}

impl Engine {
    /// Starts the engine and brings back the torrents it had. Blocking.
    pub fn start(cfg: Config) -> anyhow::Result<Engine> {
        std::fs::create_dir_all(&cfg.folder).with_context(|| format!("can't create {}", cfg.folder.display()))?;
        std::fs::create_dir_all(&cfg.state_dir)?;
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("torrent")
            .enable_all()
            .build()?;
        let opts = SessionOptions {
            dht: cfg.dht.then(|| DhtSessionConfig {
                // Its own default lives under the home folder, which Android apps don't have
                persistence: Some(DhtPersistenceConfig {
                    config_filename: Some(cfg.state_dir.join("dht.json")),
                    ..Default::default()
                }),
                ..Default::default()
            }),
            persistence: Some(SessionPersistenceConfig::Json { folder: Some(cfg.state_dir.clone()) }),
            fastresume: true,
            disable_trackers: !cfg.trackers,
            disable_local_service_discovery: !cfg.dht,
            listen: Some(ListenerOptions {
                mode: ListenerMode::TcpAndUtp,
                listen_addr: (Ipv6Addr::UNSPECIFIED, cfg.port).into(),
                ..Default::default()
            }),
            ratelimits: LimitsConfig { upload_bps: limit(cfg.up_limit), download_bps: limit(cfg.down_limit) },
            ..Default::default()
        };
        let session = rt.block_on(Session::new_with_opts(cfg.folder.clone(), opts))?;
        Ok(Engine { rt, session })
    }

    /// The port people can reach this engine on, once listening.
    pub fn listen_port(&self) -> Option<u16> {
        self.session.listen_addr().map(|a| a.port()).filter(|p| *p != 0)
    }

    fn add(&self, what: AddTorrent<'_>, opts: AddTorrentOptions) -> anyhow::Result<Added> {
        let r = self.rt.block_on(self.session.add_torrent(what, Some(opts)))?;
        let (id, h) = match r {
            AddTorrentResponse::Added(id, h) | AddTorrentResponse::AlreadyManaged(id, h) => (id, h),
            AddTorrentResponse::ListOnly(_) => anyhow::bail!("the torrent was only listed"),
        };
        Ok(Added { id, info_hash: h.info_hash().as_string(), name: name_of(&h) })
    }

    fn opts() -> AddTorrentOptions {
        // Picks up what was saved after a restart instead of starting over
        AddTorrentOptions { overwrite: true, ..Default::default() }
    }

    /// A magnet link. Blocks until the torrent's details arrive from other people.
    pub fn add_magnet(&self, uri: &str) -> anyhow::Result<Added> {
        self.add(AddTorrent::from_url(uri), Self::opts())
    }

    /// A .torrent file's address. Fetched by the engine.
    pub fn add_url(&self, url: &str) -> anyhow::Result<Added> {
        self.add(AddTorrent::from_url(url), Self::opts())
    }

    /// A .torrent file's contents.
    pub fn add_bytes(&self, bytes: &[u8]) -> anyhow::Result<Added> {
        self.add(AddTorrent::from_bytes(bytes.to_vec()), Self::opts())
    }

    /// A .torrent file, also connecting straight to [peer].
    pub fn add_bytes_with_peer(&self, bytes: &[u8], peer: impl ToSocketAddrs) -> anyhow::Result<Added> {
        let peers = peer.to_socket_addrs()?.collect::<Vec<SocketAddr>>();
        self.add(AddTorrent::from_bytes(bytes.to_vec()), AddTorrentOptions { initial_peers: Some(peers), ..Self::opts() })
    }

    /// Like [add_bytes_with_peer], but paused, so files can be chosen first.
    pub fn add_bytes_with_peer_paused(&self, bytes: &[u8], peer: impl ToSocketAddrs) -> anyhow::Result<Added> {
        let peers = peer.to_socket_addrs()?.collect::<Vec<SocketAddr>>();
        self.add(
            AddTorrent::from_bytes(bytes.to_vec()),
            AddTorrentOptions { initial_peers: Some(peers), paused: true, ..Self::opts() },
        )
    }

    fn handle(&self, id: usize) -> anyhow::Result<Arc<ManagedTorrent>> {
        self.session.get(TorrentIdOrHash::Id(id)).with_context(|| format!("no torrent {id}"))
    }

    pub fn pause(&self, id: usize) -> anyhow::Result<()> {
        let h = self.handle(id)?;
        self.rt.block_on(self.session.pause(&h))
    }

    pub fn resume(&self, id: usize) -> anyhow::Result<()> {
        let h = self.handle(id)?;
        self.rt.block_on(self.session.unpause(&h))
    }

    /// Forgets a torrent; [delete_files] also removes what it downloaded.
    pub fn remove(&self, id: usize, delete_files: bool) -> anyhow::Result<()> {
        self.rt.block_on(self.session.delete(TorrentIdOrHash::Id(id), delete_files))
    }

    /// Downloads only these files of the torrent (indexes into [Stat::files]).
    pub fn select_files(&self, id: usize, files: &[usize]) -> anyhow::Result<()> {
        let h = self.handle(id)?;
        let set: HashSet<usize> = files.iter().copied().collect();
        self.rt.block_on(async {
            // A just-added torrent first checks what is already on disk, and can't be changed until then
            tokio::time::timeout(INIT_WAIT, h.wait_until_initialized())
                .await
                .map_err(|_| anyhow::anyhow!("still checking the files already on disk, try again shortly"))??;
            self.session.update_only_files(&h, &set).await
        })
    }

    pub fn set_limits(&self, down: u32, up: u32) {
        self.session.ratelimits.set_download_bps(limit(down));
        self.session.ratelimits.set_upload_bps(limit(up));
    }

    pub fn stats(&self) -> Vec<Stat> {
        let mut v = self.session.with_torrents(|it| it.map(|(id, h)| stat(id, h)).collect::<Vec<_>>());
        v.sort_by_key(|s| s.id);
        v
    }

    /// Stops every torrent and saves where each got to; the engine is unusable after.
    pub fn halt(&self) {
        self.rt.block_on(self.session.stop());
    }

    /// Like [halt], then ends the engine's threads.
    pub fn stop(self) {
        self.halt();
        self.rt.shutdown_timeout(Duration::from_secs(5));
    }
}

fn name_of(h: &ManagedTorrent) -> String {
    h.name().unwrap_or_else(|| h.info_hash().as_string())
}

fn stat(id: usize, h: &ManagedTorrent) -> Stat {
    let st = h.stats();
    let live = st.live.as_ref();
    let only = h.only_files().map(|v| v.into_iter().collect::<HashSet<_>>());
    let files = h
        .with_metadata(|m| {
            m.file_infos
                .iter()
                .enumerate()
                .map(|(i, f)| FileStat {
                    name: f.relative_filename.to_string_lossy().into_owned(),
                    size: f.len,
                    done: st.file_progress.get(i).copied().unwrap_or(0),
                    selected: only.as_ref().is_none_or(|o| o.contains(&i)),
                })
                .collect()
        })
        .unwrap_or_default();
    Stat {
        id,
        info_hash: h.info_hash().as_string(),
        name: name_of(h),
        state: match st.state {
            TorrentStatsState::Initializing { .. } => "initializing",
            TorrentStatsState::Live => "live",
            TorrentStatsState::Paused => "paused",
            TorrentStatsState::Error => "error",
        }
        .to_string(),
        done: st.progress_bytes,
        total: st.total_bytes,
        uploaded: st.uploaded_bytes,
        down_bps: live.map(|l| l.download_speed.as_bytes()).unwrap_or(0),
        up_bps: live.map(|l| l.upload_speed.as_bytes()).unwrap_or(0),
        peers: live.map(|l| l.snapshot.peer_stats.live).unwrap_or(0),
        finished: st.finished,
        error: st.error.clone(),
        files,
    }
}

/// A .torrent for a file or folder on disk (for tests and sharing).
pub fn create_torrent_for(path: &Path) -> anyhow::Result<Vec<u8>> {
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build()?;
    let r = rt.block_on(async {
        librqbit::create_torrent(
            path,
            CreateTorrentOptions { piece_length: Some(256 * 1024), ..Default::default() },
            &BlockingSpawner::new(1),
        )
        .await
    })?;
    Ok(r.as_bytes()?.to_vec())
}

/// A hand-made single-piece .torrent with one file at [path] (for tests).
pub fn torrent_with_path(path: &[&str], len: u64) -> Vec<u8> {
    fn s(out: &mut Vec<u8>, v: &[u8]) {
        out.extend_from_slice(format!("{}:", v.len()).as_bytes());
        out.extend_from_slice(v);
    }
    let mut b = b"d4:infod5:filesld6:lengthi".to_vec();
    b.extend_from_slice(format!("{len}e4:pathl").as_bytes());
    for p in path {
        s(&mut b, p.as_bytes());
    }
    b.extend_from_slice(b"eee4:name");
    s(&mut b, b"evil");
    b.extend_from_slice(format!("12:piece lengthi{len}e6:pieces").as_bytes());
    s(&mut b, &[0u8; 20]);
    b.extend_from_slice(b"ee");
    b
}
