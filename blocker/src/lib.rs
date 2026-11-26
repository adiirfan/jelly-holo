//! Jelly Holo's ad and tracker blocker: a thin layer over adblock-rust that
//! the app calls (through `jni`) for every request a page makes.

use std::sync::RwLock;

use adblock::lists::ParseOptions;
use adblock::request::Request;
use adblock::{Engine, FilterSet};

mod jni;

/// Parse filter lists (EasyList syntax) and return the engine in its saved
/// form, ready for [`Blocker::load`].
pub fn compile(lists: &[&str]) -> Vec<u8> {
    let mut set = FilterSet::new(false);
    for list in lists {
        set.add_filter_list(list.to_string(), ParseOptions::default());
    }
    Engine::new_with_filter_set(set).serialize()
}

/// The loaded engine, shared by every page-loading thread.
pub struct Blocker {
    engine: RwLock<Option<Engine>>,
}

impl Blocker {
    pub const fn new() -> Blocker {
        Blocker { engine: RwLock::new(None) }
    }

    /// Swap in an engine from its saved form. On failure the current one
    /// stays, so a bad file never turns blocking off.
    pub fn load(&self, bytes: &[u8]) -> bool {
        let mut engine = Engine::default();
        if engine.deserialize(bytes).is_err() {
            return false;
        }
        warm_up(&engine);
        *self.engine.write().unwrap_or_else(|e| e.into_inner()) = Some(engine);
        true
    }

    pub fn is_loaded(&self) -> bool {
        self.engine.read().map(|e| e.is_some()).unwrap_or(false)
    }

    /// Whether a request for `url`, made by the page at `page_url`, should
    /// be dropped. `accept` is the request's Accept header. Anything that
    /// can't be checked is allowed.
    pub fn should_block(&self, url: &str, page_url: &str, accept: &str) -> bool {
        let Ok(guard) = self.engine.read() else { return false };
        let Some(engine) = guard.as_ref() else { return false };
        if !(url.starts_with("http://") || url.starts_with("https://")) {
            return false;
        }
        let Ok(request) = Request::new(url, page_url, request_type(url, accept), "GET") else {
            return false;
        };
        engine.check_network_request(&request).should_block()
    }
}

impl Default for Blocker {
    fn default() -> Self {
        Blocker::new()
    }
}

/// Requests of every kind, run once before an engine is used: the engine
/// sets parts of itself up on first use, which would otherwise land on the
/// first page load.
fn warm_up(engine: &Engine) {
    const PAGE: &str = "https://www.example.com/";
    const URLS: [(&str, &str); 8] = [
        ("https://static.example.net/x.js", "*/*"),
        ("https://ads.example.net/banner.png", "image/webp,*/*"),
        ("https://px.example.net/pixel?id=1&ev=PageView", "*/*"),
        ("https://cdn.example.net/app.css", "text/css,*/*;q=0.1"),
        ("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "*/*"),
        ("https://t.example.net/beacon/track", "*/*"),
        ("https://www.google-analytics.com/g/collect?tid=G-1", "*/*"),
        ("https://www.example.com/api/data.json", "application/json"),
    ];
    for (url, accept) in URLS {
        if let Ok(r) = Request::new(url, PAGE, request_type(url, accept), "GET") {
            engine.check_network_request(&r);
        }
    }
}

/// The app's single blocker.
pub static BLOCKER: Blocker = Blocker::new();

/// Best guess at what kind of file a request fetches, in adblock's terms.
/// WebView doesn't say, so the Accept header goes first, then the extension.
pub fn request_type(url: &str, accept: &str) -> &'static str {
    let accept = accept.to_ascii_lowercase();
    if accept.starts_with("text/html") {
        return "subdocument";
    }
    if accept.starts_with("text/css") {
        return "stylesheet";
    }
    if accept.starts_with("image/") {
        return "image";
    }
    if accept.starts_with("video/") || accept.starts_with("audio/") {
        return "media";
    }
    if accept.starts_with("application/json") {
        return "xmlhttprequest";
    }
    let path = url.split(['?', '#']).next().unwrap_or("");
    let file = path.rsplit('/').next().unwrap_or("");
    let ext = file.rsplit_once('.').map(|(_, e)| e.to_ascii_lowercase()).unwrap_or_default();
    match ext.as_str() {
        "js" | "mjs" => "script",
        "css" => "stylesheet",
        "png" | "jpg" | "jpeg" | "gif" | "webp" | "avif" | "svg" | "ico" => "image",
        "woff" | "woff2" | "ttf" | "otf" => "font",
        "mp4" | "webm" | "m3u8" => "media",
        _ => "other",
    }
}
