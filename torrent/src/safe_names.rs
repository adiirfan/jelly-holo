//! Saving torrents under names Android's shared storage accepts.
//!
//! Android refuses to create files or folders whose names contain
//! `" * / : < > ? \ |` or control characters ("Operation not permitted"), and
//! torrent names often have them ("Part 1: The Start"). This storage works
//! like librqbit's own, but swaps each of those for a look-alike that is
//! allowed (`:` → `：`), and keeps every name within 255 bytes.

use std::{
    any::TypeId,
    fs::{File, OpenOptions},
    os::unix::fs::FileExt,
    path::{Component, Path, PathBuf},
    sync::RwLock,
};

use anyhow::Context;
use librqbit::{
    ManagedTorrentShared, TorrentMetadata,
    storage::{
        BoxStorageFactory, StorageFactory, StorageFactoryExt, TorrentStorage, filesystem::FilesystemStorageFactory,
    },
};

/// One file or folder name, made acceptable to Android.
pub fn safe_name(name: &str) -> String {
    let mut out: String = name
        .chars()
        .map(|c| match c {
            ':' => '：',
            '*' => '＊',
            '?' => '？',
            '"' => '＂',
            '<' => '＜',
            '>' => '＞',
            '|' => '｜',
            '\\' => '＼',
            c if c.is_control() => '_',
            c => c,
        })
        .collect();
    // Names are limited to 255 bytes; keep the ending (it holds the file type)
    if out.len() > MAX_NAME {
        let ext = out.rfind('.').filter(|&i| out.len() - i <= 16).map(|i| out[i..].to_string()).unwrap_or_default();
        let mut cut = MAX_NAME - ext.len();
        while !out.is_char_boundary(cut) {
            cut -= 1;
        }
        out.truncate(cut);
        out.push_str(&ext);
    }
    out
}

const MAX_NAME: usize = 255;

/// [path] with every part made safe; parts like ".." are dropped.
pub fn safe_relative(path: &Path) -> PathBuf {
    path.components()
        .filter_map(|c| match c {
            Component::Normal(s) => Some(safe_name(&s.to_string_lossy())),
            _ => None,
        })
        .collect()
}

/// A torrent's own folder below the download folder, as librqbit names it: none for a
/// single file, else the torrent's name (or, without one, its longest file's name).
pub fn folder_name(m: &TorrentMetadata) -> PathBuf {
    if m.file_infos.len() < 2 {
        return PathBuf::new();
    }
    if let Some(name) = m.info.name().filter(|n| !n.is_empty()) {
        return PathBuf::from(name.as_ref());
    }
    m.file_infos
        .iter()
        .max_by_key(|f| f.len)
        .and_then(|f| f.relative_filename.file_stem())
        .map(PathBuf::from)
        .unwrap_or_default()
}

/// Where a torrent's file really is, below [root], with every name made safe.
pub fn on_disk(root: &Path, folder: &Path, relative: &Path) -> PathBuf {
    root.join(safe_relative(folder)).join(safe_relative(relative))
}

#[derive(Clone)]
pub struct SafeStorageFactory {
    pub root: PathBuf,
}

impl StorageFactory for SafeStorageFactory {
    type Storage = SafeStorage;

    fn create(&self, _shared: &ManagedTorrentShared, metadata: &TorrentMetadata) -> anyhow::Result<SafeStorage> {
        Ok(SafeStorage { root: self.root.clone(), folder: folder_name(metadata), files: Vec::new() })
    }

    // librqbit only saves torrents kept by its own file storage; this is that, with other names
    fn is_type_id(&self, type_id: TypeId) -> bool {
        type_id == TypeId::of::<Self>() || type_id == TypeId::of::<FilesystemStorageFactory>()
    }

    fn clone_box(&self) -> BoxStorageFactory {
        self.clone().boxed()
    }
}

pub struct SafeStorage {
    root: PathBuf,
    /// The torrent's own folder below [root], as the torrent names it.
    folder: PathBuf,
    /// One per file in the torrent; None for padding files and once handed on by [take].
    files: Vec<RwLock<Option<File>>>,
}

impl SafeStorage {
    fn with<T>(&self, id: usize, f: impl FnOnce(&File) -> std::io::Result<T>) -> anyhow::Result<T> {
        let slot = self.files.get(id).context("no such file")?;
        let guard = slot.read().map_err(|_| anyhow::anyhow!("file lock poisoned"))?;
        let file = guard.as_ref().context("file not open")?;
        Ok(f(file)?)
    }

    fn path(&self, relative: &Path) -> PathBuf {
        on_disk(&self.root, &self.folder, relative)
    }
}

impl TorrentStorage for SafeStorage {
    fn init(&mut self, _shared: &ManagedTorrentShared, metadata: &TorrentMetadata) -> anyhow::Result<()> {
        let mut files = Vec::with_capacity(metadata.file_infos.len());
        for fi in metadata.file_infos.iter() {
            if fi.attrs.padding {
                files.push(RwLock::new(None));
                continue;
            }
            let full = self.path(&fi.relative_filename);
            std::fs::create_dir_all(full.parent().context("no parent folder")?)
                .with_context(|| format!("error creating the folder for {full:?}"))?;
            // Existing files are kept and checked: that is how a download resumes
            let file = OpenOptions::new()
                .create(true)
                .truncate(false)
                .read(true)
                .write(true)
                .open(&full)
                .with_context(|| format!("error opening {full:?} in read/write mode"))?;
            files.push(RwLock::new(Some(file)));
        }
        self.files = files;
        Ok(())
    }

    fn pread_exact(&self, file_id: usize, offset: u64, buf: &mut [u8]) -> anyhow::Result<()> {
        self.with(file_id, |f| f.read_exact_at(buf, offset))
    }

    fn pwrite_all(&self, file_id: usize, offset: u64, buf: &[u8]) -> anyhow::Result<()> {
        self.with(file_id, |f| f.write_all_at(buf, offset))
    }

    fn remove_file(&self, _file_id: usize, filename: &Path) -> anyhow::Result<()> {
        Ok(std::fs::remove_file(self.path(filename))?)
    }

    fn remove_directory_if_empty(&self, path: &Path) -> anyhow::Result<()> {
        let dir = self.path(path);
        if dir.is_dir() && std::fs::read_dir(&dir)?.next().is_none() {
            std::fs::remove_dir(&dir).with_context(|| format!("error removing {dir:?}"))?;
        }
        Ok(())
    }

    fn ensure_file_length(&self, file_id: usize, length: u64) -> anyhow::Result<()> {
        self.with(file_id, |f| f.set_len(length))
    }

    /// Hands the open files to a new storage and leaves this one empty (used when pausing).
    fn take(&self) -> anyhow::Result<Box<dyn TorrentStorage>> {
        let files = self
            .files
            .iter()
            .map(|slot| RwLock::new(slot.write().ok().and_then(|mut f| f.take())))
            .collect();
        Ok(Box::new(SafeStorage { root: self.root.clone(), folder: self.folder.clone(), files }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn forbidden_characters_become_look_alikes() {
        assert_eq!(safe_name(r#"a:b*c?d"e<f>g|h\i"#), "a：b＊c？d＂e＜f＞g｜h＼i");
        assert_eq!(safe_name("tab\there"), "tab_here");
        assert_eq!(safe_name("Normal name (1).mkv"), "Normal name (1).mkv");
    }

    #[test]
    fn long_names_are_cut_to_255_bytes_keeping_the_file_type() {
        let long = format!("{}.webp", "あ".repeat(120));
        let safe = safe_name(&long);
        assert!(safe.len() <= 255, "{} bytes", safe.len());
        assert!(safe.ends_with(".webp"));
    }

    #[test]
    fn paths_are_made_safe_inside_the_root() {
        let root = Path::new("/dl");
        assert_eq!(
            on_disk(root, Path::new("Part 1: Start"), Path::new("cd:1/01?.mp3")),
            PathBuf::from("/dl/Part 1： Start/cd：1/01？.mp3")
        );
        assert_eq!(on_disk(root, Path::new(""), Path::new("../x:y")), PathBuf::from("/dl/x：y"));
    }
}
