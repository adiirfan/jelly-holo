//! Build-time step: compile EasyList-style lists into the engine's saved
//! form, shipped in the app as `assets/adblock.dat`.
//! Usage: compile_lists <out> <list>...

use std::{env, fs, process::exit};

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.len() < 2 {
        eprintln!("usage: compile_lists <out> <list>...");
        exit(2);
    }
    let texts: Vec<String> = args[1..]
        .iter()
        .map(|p| fs::read_to_string(p).unwrap_or_else(|e| panic!("{p}: {e}")))
        .collect();
    let refs: Vec<&str> = texts.iter().map(String::as_str).collect();
    let bytes = jellyblock::compile(&refs);
    fs::write(&args[0], &bytes).unwrap_or_else(|e| panic!("{}: {e}", args[0]));
    println!("compile_lists: {} lists -> {} ({} KB)", refs.len(), args[0], bytes.len() / 1024);
}
