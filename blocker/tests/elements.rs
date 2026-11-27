use jellyblock::ElementRules;

fn rules(text: &str) -> ElementRules {
    let r = ElementRules::new();
    r.set(text);
    r
}

#[test]
fn hides_on_its_site() {
    let r = rules("example.com##.promo");
    assert_eq!(r.hide_css("https://example.com/news/1"), ".promo{display:none!important}\n");
}

#[test]
fn covers_subdomains() {
    let r = rules("example.com##.promo");
    assert!(r.hide_css("https://www.example.com/").contains(".promo"));
    assert!(r.hide_css("https://m.news.example.com/").contains(".promo"));
}

#[test]
fn not_on_other_sites() {
    let r = rules("example.com##.promo");
    assert_eq!(r.hide_css("https://other.org/"), "");
    assert_eq!(r.hide_css("https://notexample.com/"), "");
}

#[test]
fn one_line_per_selector() {
    let r = rules("example.com##.a\nexample.com###b > div:nth-of-type(2)\n");
    let css = r.hide_css("https://example.com/");
    let mut lines: Vec<&str> = css.lines().collect();
    lines.sort();
    assert_eq!(
        lines,
        vec!["#b > div:nth-of-type(2){display:none!important}", ".a{display:none!important}"]
    );
}

#[test]
fn no_rules_no_css() {
    assert_eq!(ElementRules::new().hide_css("https://example.com/"), "");
    assert_eq!(rules("").hide_css("https://example.com/"), "");
}

#[test]
fn unsafe_selector_dropped() {
    let r = rules("example.com##.a{}body\nexample.com##.ok\nexample.com##.x</style>");
    assert_eq!(r.hide_css("https://example.com/"), ".ok{display:none!important}\n");
}

#[test]
fn replacing_rules_forgets_old() {
    let r = rules("example.com##.old");
    r.set("example.com##.new");
    assert_eq!(r.hide_css("https://example.com/"), ".new{display:none!important}\n");
}

#[test]
fn bad_url_gives_nothing() {
    let r = rules("example.com##.promo");
    assert_eq!(r.hide_css("not a url"), "");
    assert_eq!(r.hide_css(""), "");
}
