//! The calls the Kotlin `org.lineageos.jelly.torrent.NativeTorrent` object
//! makes. Nothing may panic across this boundary: every call catches and
//! answers with an error instead. Results that carry data are JSON.

use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    path::PathBuf,
    sync::{Arc, RwLock},
};

use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JIntArray, JObject, JString},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jstring},
};

use crate::{Config, Engine};

static ENGINE: RwLock<Option<Arc<Engine>>> = RwLock::new(None);

fn engine() -> anyhow::Result<Arc<Engine>> {
    ENGINE.read().map_err(|_| anyhow::anyhow!("engine lock broken"))?.clone().ok_or_else(|| anyhow::anyhow!("torrents are off"))
}

fn flag(b: bool) -> jboolean {
    if b { JNI_TRUE } else { JNI_FALSE }
}

fn string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(Into::into)
}

fn out(env: &mut JNIEnv, s: Option<String>) -> jstring {
    match s {
        Some(s) => env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

fn error_json(e: impl std::fmt::Display) -> String {
    serde_json::json!({ "error": format!("{e:#}") }).to_string()
}

/// Runs [f], turning an error or a panic into `{"error": …}`.
fn json<T: serde::Serialize>(f: impl FnOnce() -> anyhow::Result<T>) -> String {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(v)) => serde_json::to_string(&v).unwrap_or_else(error_json),
        Ok(Err(e)) => error_json(format!("{e:#}")),
        Err(_) => error_json("the torrent engine crashed"),
    }
}

/// Runs [f]; true when it worked.
fn ok(f: impl FnOnce() -> anyhow::Result<()>) -> bool {
    matches!(catch_unwind(AssertUnwindSafe(f)), Ok(Ok(())))
}

/// Lets the engine check HTTPS certificates with Android's own checker.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_lineageos_jelly_torrent_NativeTorrent_nativeInit(
    env: JNIEnv,
    _class: JClass,
    context: JObject,
) -> jboolean {
    let raw_env = env.get_raw();
    let raw_ctx = context.as_raw();
    let r = catch_unwind(AssertUnwindSafe(|| {
        // Safety: the pointers are this call's own, valid for its duration
        let mut u = unsafe { jni22::EnvUnowned::from_raw(raw_env as *mut _) };
        let outcome = u
            .with_env(|e| -> Result<(), jni22::errors::Error> {
                let ctx = unsafe { jni22::objects::JObject::from_raw(e, raw_ctx as _) };
                rustls_platform_verifier::android::init_with_env(e, ctx)
            })
            .into_outcome();
        matches!(outcome, jni22::Outcome::Ok(()))
    }));
    flag(r.unwrap_or(false))
}

/// Starts the engine (stopping any running one). Answers null, or why it couldn't.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_lineageos_jelly_torrent_NativeTorrent_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    folder: JString,
    state_dir: JString,
    port: jint,
    down: jint,
    up: jint,
) -> jstring {
    let (folder, state_dir) = (string(&mut env, &folder), string(&mut env, &state_dir));
    let r = catch_unwind(AssertUnwindSafe(|| -> anyhow::Result<()> {
        let (Some(folder), Some(state_dir)) = (folder, state_dir) else { anyhow::bail!("bad folder") };
        stop_running();
        let cfg = Config {
            port: u16::try_from(port).unwrap_or(0),
            down_limit: down.max(0) as u32,
            up_limit: up.max(0) as u32,
            ..Config::new(PathBuf::from(folder), PathBuf::from(state_dir))
        };
        let e = Engine::start(cfg)?;
        *ENGINE.write().map_err(|_| anyhow::anyhow!("engine lock broken"))? = Some(Arc::new(e));
        Ok(())
    }));
    let err = match r {
        Ok(Ok(())) => None,
        Ok(Err(e)) => Some(format!("{e:#}")),
        Err(_) => Some("the torrent engine crashed".to_string()),
    };
    out(&mut env, err)
}

fn stop_running() {
    let Some(e) = ENGINE.write().ok().and_then(|mut g| g.take()) else { return };
    match Arc::try_unwrap(e) {
        Ok(e) => e.stop(),
        // A call is still running on it: stop the torrents now, the threads end when it returns
        Err(shared) => shared.halt(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_lineageos_jelly_torrent_NativeTorrent_nativeStop(_env: JNIEnv, _class: JClass) {
    let _ = catch_unwind(stop_running);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_lineageos_jelly_torrent_NativeTorrent_nativeAddMagnet(
    mut env: JNIEnv,
    _class: JClass,
    uri: JString,
) -> jstring {
    let uri = string(&mut env, &uri);
    let r = json(|| engine()?.add_magnet(&uri.ok_or_else(|| anyhow::anyhow!("bad link"))?));
    out(&mut env, Some(r))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_lineageos_jelly_torrent_NativeTorrent_nativeAddUrl(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) -> jstring {
    let url = string(&mut env, &url);
    let r = json(|| engine()?.add_url(&url.ok_or_else(|| anyhow::anyhow!("bad address"))?));
    out(&mut env, Some(r))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_lineageos_jelly_torrent_NativeTorrent_nativeAddBytes(
    mut env: JNIEnv,
    _class: JClass,
    bytes: JByteArray,
) -> jstring {
    let bytes = env.convert_byte_array(&bytes).ok();
    let r = json(|| engine()?.add_bytes(&bytes.ok_or_else(|| anyhow::anyhow!("couldn't read the file"))?));
    out(&mut env, Some(r))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_lineageos_jelly_torrent_NativeTorrent_nativePause(
    _env: JNIEnv,
    _class: JClass,
    id: jint,
) -> jboolean {
    flag(ok(|| engine()?.pause(id as usize)))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_lineageos_jelly_torrent_NativeTorrent_nativeResume(
    _env: JNIEnv,
    _class: JClass,
    id: jint,
) -> jboolean {
    flag(ok(|| engine()?.resume(id as usize)))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_lineageos_jelly_torrent_NativeTorrent_nativeRemove(
    _env: JNIEnv,
    _class: JClass,
    id: jint,
    delete_files: jboolean,
) -> jboolean {
    flag(ok(|| engine()?.remove(id as usize, delete_files == JNI_TRUE)))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_lineageos_jelly_torrent_NativeTorrent_nativeSelectFiles(
    env: JNIEnv,
    _class: JClass,
    id: jint,
    files: JIntArray,
) -> jboolean {
    let n = env.get_array_length(&files).unwrap_or(0).max(0) as usize;
    let mut buf = vec![0 as jint; n];
    let read = env.get_int_array_region(&files, 0, &mut buf).is_ok();
    flag(read && ok(|| {
        let picked: Vec<usize> = buf.iter().filter(|i| **i >= 0).map(|i| *i as usize).collect();
        engine()?.select_files(id as usize, &picked)
    }))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_lineageos_jelly_torrent_NativeTorrent_nativeSetLimits(
    _env: JNIEnv,
    _class: JClass,
    down: jint,
    up: jint,
) {
    let _ = ok(|| {
        engine()?.set_limits(down.max(0) as u32, up.max(0) as u32);
        Ok(())
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_lineageos_jelly_torrent_NativeTorrent_nativeListenPort(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    catch_unwind(|| engine().ok().and_then(|e| e.listen_port()).map(jint::from).unwrap_or(0)).unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_lineageos_jelly_torrent_NativeTorrent_nativeStats(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let r = json(|| Ok(engine()?.stats()));
    out(&mut env, Some(r))
}
