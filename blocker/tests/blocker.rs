use jellyblock::{compile, request_type, Blocker};

const RULES: &str = "\
||ads.example.com^
@@||ads.example.com/allowed.js
||tracker.example.net^$third-party
||cdn.example.org/*.png$image
||cdn.example.org/lib.js$script
";
const PAGE: &str = "https://news.example.org/";
const ANY: &str = "*/*";

fn loaded() -> Blocker {
    let b = Blocker::new();
    assert!(b.load(&compile(&[RULES])));
    b
}

#[test]
fn blocks_listed_host() {
    assert!(loaded().should_block("https://ads.example.com/banner.js", PAGE, ANY));
}

#[test]
fn allows_normal_request() {
    assert!(!loaded().should_block("https://news.example.org/style.css", PAGE, "text/css,*/*;q=0.1"));
}

#[test]
fn exception_wins() {
    assert!(!loaded().should_block("https://ads.example.com/allowed.js", PAGE, ANY));
}

#[test]
fn third_party_only() {
    let b = loaded();
    assert!(b.should_block("https://tracker.example.net/t.gif", PAGE, ANY));
    assert!(!b.should_block("https://tracker.example.net/t.gif", "https://tracker.example.net/", ANY));
}

#[test]
fn type_options_respected() {
    let b = loaded();
    assert!(b.should_block("https://cdn.example.org/a.png", PAGE, "image/avif,image/webp,*/*"));
    assert!(b.should_block("https://cdn.example.org/lib.js", PAGE, ANY));
    assert!(!b.should_block("https://cdn.example.org/a.js", PAGE, ANY));
}

#[test]
fn request_type_guess() {
    assert_eq!(request_type("https://x.org/a.js?v=1", "*/*"), "script");
    assert_eq!(request_type("https://x.org/s", "text/css,*/*;q=0.1"), "stylesheet");
    assert_eq!(request_type("https://x.org/p", "image/webp,*/*"), "image");
    assert_eq!(request_type("https://x.org/f.woff2", "*/*"), "font");
    assert_eq!(request_type("https://x.org/api", "application/json"), "xmlhttprequest");
    assert_eq!(request_type("https://x.org/", "*/*"), "other");
}

#[test]
fn nothing_loaded_allows_all() {
    assert!(!Blocker::new().should_block("https://ads.example.com/banner.js", PAGE, ANY));
}

#[test]
fn load_garbage_keeps_previous() {
    let b = loaded();
    assert!(!b.load(b"not an engine"));
    assert!(b.should_block("https://ads.example.com/banner.js", PAGE, ANY));
}

#[test]
fn bad_urls_are_allowed() {
    let b = loaded();
    for url in ["", "not a url", "data:image/png;base64,AAAA", "blob:https://news.example.org/x"] {
        assert!(!b.should_block(url, PAGE, ANY), "{url}");
    }
}

#[test]
fn checks_from_many_threads() {
    let b = loaded();
    let cases = [
        ("https://ads.example.com/banner.js", true),
        ("https://news.example.org/style.css", false),
        ("https://tracker.example.net/t.gif", true),
        ("https://ads.example.com/allowed.js", false),
    ];
    std::thread::scope(|s| {
        for _ in 0..8 {
            s.spawn(|| {
                for i in 0..1000 {
                    let (url, want) = cases[i % cases.len()];
                    assert_eq!(b.should_block(url, PAGE, ANY), want, "{url}");
                }
            });
        }
    });
}

#[test]
fn blocker_is_sync() {
    fn assert_sync<T: Send + Sync>() {}
    assert_sync::<Blocker>();
}

/// Against the real downloaded lists (skipped when they aren't fetched).
#[test]
fn real_lists_block_known_ad_servers() {
    let (Ok(a), Ok(b)) = (
        std::fs::read_to_string("lists/easylist.txt"),
        std::fs::read_to_string("lists/easyprivacy.txt"),
    ) else {
        eprintln!("lists not fetched; skipping");
        return;
    };
    let blk = Blocker::new();
    assert!(blk.load(&compile(&[&a, &b])));
    assert!(blk.should_block("https://securepubads.g.doubleclick.net/tag/js/gpt.js", PAGE, ANY));
    assert!(blk.should_block("https://www.google-analytics.com/analytics.js", PAGE, ANY));
    assert!(!blk.should_block("https://news.example.org/app.js", PAGE, ANY));
    assert!(!blk.should_block("https://upload.wikimedia.org/wikipedia/commons/a.png", "https://en.wikipedia.org/", "image/webp,*/*"));
    let t = std::time::Instant::now();
    for _ in 0..1000 {
        blk.should_block("https://cdn.example.org/static/js/main.123.js", PAGE, ANY);
    }
    eprintln!("1000 checks: {:?}", t.elapsed());
}
