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

/// The first check after loading must not pay the engine's one-off set-up
/// cost (it was 0.6 ms here and 8 ms on the phone, during a page load).
#[test]
fn first_check_after_load_is_fast() {
    let (Ok(a), Ok(b)) = (
        std::fs::read_to_string("lists/easylist.txt"),
        std::fs::read_to_string("lists/easyprivacy.txt"),
    ) else {
        eprintln!("lists not fetched; skipping");
        return;
    };
    let blk = Blocker::new();
    assert!(blk.load(&compile(&[&a, &b])));
    let t = std::time::Instant::now();
    blk.should_block("https://s0.cnn.com/x.js", "https://edition.cnn.com/", ANY);
    let first = t.elapsed();
    if cfg!(debug_assertions) {
        eprintln!("timing only checked in release builds ({first:?})");
        return;
    }
    assert!(first.as_micros() < 150, "first check took {first:?}");
}

/// On-device timing for one URL: BENCH_URL, BENCH_PAGE (run with --ignored).
#[test]
#[ignore]
fn bench_url() {
    let a = std::fs::read_to_string("lists/easylist.txt").unwrap();
    let b = std::fs::read_to_string("lists/easyprivacy.txt").unwrap();
    let blk = Blocker::new();
    blk.load(&compile(&[&a, &b]));
    let url = std::env::var("BENCH_URL").unwrap();
    let page = std::env::var("BENCH_PAGE").unwrap();
    let t = std::time::Instant::now();
    for _ in 0..200 {
        blk.should_block(&url, &page, ANY);
    }
    eprintln!("bench {} : {:?} per check", url, t.elapsed() / 200);
}

#[test]
fn frame_requests_are_subdocuments() {
    const FRAME: &str = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    assert_eq!(request_type("https://ads.example.net/slot?id=3", FRAME), "subdocument");
    let b = Blocker::new();
    assert!(b.load(&compile(&["||adframes.example.net^$subdocument"])));
    assert!(b.should_block("https://adframes.example.net/slot", PAGE, FRAME));
    assert!(!b.should_block("https://adframes.example.net/a.js", PAGE, "*/*"));
}

const NEWS: &str = "https://www.some-news-site.com/article";

#[test]
fn page_jump_to_listed_host_blocked() {
    let b = Blocker::new();
    assert!(b.load(&compile(&["||adredirect.example^"])));
    assert!(b.should_block_page("https://adredirect.example/go?id=1", NEWS));
    assert!(b.should_block_page("https://www.adredirect.example/", NEWS));
}

#[test]
fn page_jump_to_normal_site_allowed() {
    let b = Blocker::new();
    assert!(b.load(&compile(&["||adredirect.example^"])));
    assert!(!b.should_block_page("https://www.wikipedia.org/", NEWS));
}

#[test]
fn popup_only_rule_blocks_page_jump() {
    let b = Blocker::new();
    assert!(b.load(&compile(&["||popnet.example^$popup"])));
    assert!(b.should_block_page("https://popnet.example/x", NEWS));
    assert!(!b.should_block("https://popnet.example/x.js", NEWS, "*/*"));
}

#[test]
fn page_jump_without_a_page_allowed() {
    let b = Blocker::new();
    assert!(b.load(&compile(&["||adredirect.example^"])));
    assert!(!b.should_block_page("https://adredirect.example/", ""));
    assert!(!b.should_block_page("https://adredirect.example/", "about:blank"));
    assert!(!b.should_block_page("intent://adredirect.example/", NEWS));
}

#[test]
fn real_lists_stop_ad_redirects_only() {
    let (Ok(easylist), Ok(easyprivacy)) =
        (std::fs::read_to_string("lists/easylist.txt"), std::fs::read_to_string("lists/easyprivacy.txt"))
    else {
        eprintln!("skipped: lists not downloaded");
        return;
    };
    let b = Blocker::new();
    assert!(b.load(&compile(&[&easylist, &easyprivacy])));
    for url in [
        "https://ad.doubleclick.net/ddm/clk/123;abc",
        "https://www.popads.net/",
        "https://syndication.exoclick.com/splash.php?idzone=1",
    ] {
        assert!(b.should_block_page(url, NEWS), "{url} should be stopped");
    }
    for url in [
        "https://www.wikipedia.org/",
        "https://accounts.google.com/o/oauth2/auth?x=1",
        "https://www.google.com/url?q=https://x.com",
        "https://t.co/abc",
        "https://www.facebook.com/",
    ] {
        assert!(!b.should_block_page(url, NEWS), "{url} should be allowed");
    }
}

#[test]
fn popup_exceptions_do_not_unblock_pages() {
    let b = Blocker::new();
    assert!(b.load(&compile(&["||ads.example^\n@@||news.example^$popup"])));
    assert!(b.should_block("https://ads.example/a.js", "https://news.example/", "*/*"));
}

#[test]
fn no_lists_at_all_still_load_and_block_nothing() {
    // Every list switched off or removed: the app loads an empty engine
    let b = Blocker::new();
    let bytes = compile(&[]);
    assert!(!bytes.is_empty());
    assert!(b.load(&bytes));
    assert!(!b.should_block("https://ads.example.com/banner.js", PAGE, ANY));
}
