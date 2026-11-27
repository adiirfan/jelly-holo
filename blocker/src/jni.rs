//! The calls the Kotlin `org.lineageos.jelly.adblock.AdBlock` object makes.
//! Nothing may panic across this boundary: every call catches and answers
//! "allow" / "failed" instead.

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use crate::{compile, BLOCKER, ELEMENT_RULES};

fn flag(b: bool) -> jboolean {
    if b { JNI_TRUE } else { JNI_FALSE }
}

fn string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(Into::into)
}

#[no_mangle]
pub extern "system" fn Java_org_lineageos_jelly_adblock_AdBlock_nativeLoad(
    env: JNIEnv,
    _class: JClass,
    bytes: JByteArray,
) -> jboolean {
    let ok = catch_unwind(AssertUnwindSafe(|| {
        let Ok(data) = env.convert_byte_array(&bytes) else { return false };
        BLOCKER.load(&data)
    }));
    flag(ok.unwrap_or(false))
}

#[no_mangle]
pub extern "system" fn Java_org_lineageos_jelly_adblock_AdBlock_nativeCompile(
    mut env: JNIEnv,
    _class: JClass,
    lists: JObjectArray,
) -> jbyteArray {
    let out = catch_unwind(AssertUnwindSafe(|| {
        let n = env.get_array_length(&lists).unwrap_or(0);
        let mut texts = Vec::new();
        for i in 0..n {
            let Ok(item) = env.get_object_array_element(&lists, i) else { continue };
            if let Some(s) = string(&mut env, &JString::from(item)) {
                texts.push(s);
            }
        }
        let refs: Vec<&str> = texts.iter().map(String::as_str).collect();
        compile(&refs)
    }))
    .unwrap_or_default();
    env.byte_array_from_slice(&out).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_lineageos_jelly_adblock_AdBlock_nativeShouldBlock(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    page_url: JString,
    accept: JString,
) -> jboolean {
    let block = catch_unwind(AssertUnwindSafe(|| {
        let (Some(url), Some(page), Some(accept)) =
            (string(&mut env, &url), string(&mut env, &page_url), string(&mut env, &accept))
        else {
            return false;
        };
        BLOCKER.should_block(&url, &page, &accept)
    }));
    flag(block.unwrap_or(false))
}

#[no_mangle]
pub extern "system" fn Java_org_lineageos_jelly_adblock_AdBlock_nativeSetElementRules(
    mut env: JNIEnv,
    _class: JClass,
    rules: JString,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Some(text) = string(&mut env, &rules) {
            ELEMENT_RULES.set(&text);
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_org_lineageos_jelly_adblock_AdBlock_nativeHideCss(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) -> jstring {
    let css = catch_unwind(AssertUnwindSafe(|| {
        string(&mut env, &url).map(|u| ELEMENT_RULES.hide_css(&u)).unwrap_or_default()
    }))
    .unwrap_or_default();
    env.new_string(css).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_lineageos_jelly_adblock_AdBlock_nativeShouldBlockPage(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    from_url: JString,
) -> jboolean {
    let block = catch_unwind(AssertUnwindSafe(|| {
        let (Some(url), Some(from)) = (string(&mut env, &url), string(&mut env, &from_url)) else {
            return false;
        };
        BLOCKER.should_block_page(&url, &from)
    }));
    flag(block.unwrap_or(false))
}
