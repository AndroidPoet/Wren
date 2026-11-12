/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

//! Wren's filter engine: Brave's adblock-rust behind a small JNI surface. The engine is built
//! from list text on a background thread, cached as its serialized form, and asked about every
//! subresource from WebView's request threads, so it is built without `single-thread` and every
//! call takes `&Engine` only.

mod tunnel;

use adblock::lists::{FilterFormat, FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::{MimeType, Resource, ResourceType};
use adblock::Engine;
use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jbyteArray, jlong, jstring};
use jni::JNIEnv;

fn text(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

fn engine<'a>(handle: jlong) -> Option<&'a Engine> {
    if handle == 0 {
        None
    } else {
        // SAFETY: handles come only from `into_handle` and are freed once, by `free`.
        Some(unsafe { &*(handle as *const Engine) })
    }
}

fn into_handle(engine: Engine) -> jlong {
    Box::into_raw(Box::new(engine)) as jlong
}

fn new_string(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Lists arrive as parallel arrays: the text of each, and whether it is a hosts file.
#[no_mangle]
pub extern "system" fn Java_dev_androidpoet_wren_browser_blocking_rules_NativeEngine_create(
    mut env: JNIEnv,
    _: JClass,
    lists: JObjectArray,
    hosts_flags: JByteArray,
) -> jlong {
    let count = env.get_array_length(&lists).unwrap_or(0);
    let flags = env.convert_byte_array(&hosts_flags).unwrap_or_default();
    let mut set = FilterSet::new(false);
    for i in 0..count {
        let Ok(item) = env.get_object_array_element(&lists, i) else { continue };
        let Some(list) = text(&mut env, &JString::from(item)) else { continue };
        let hosts = flags.get(i as usize).copied().unwrap_or(0) != 0;
        let format = if hosts { FilterFormat::Hosts } else { FilterFormat::Standard };
        set.add_filter_list(list, ParseOptions { format, ..Default::default() });
    }
    into_handle(Engine::new_with_filter_set(set))
}

#[no_mangle]
pub extern "system" fn Java_dev_androidpoet_wren_browser_blocking_rules_NativeEngine_serialize(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jbyteArray {
    let Some(engine) = engine(handle) else { return std::ptr::null_mut() };
    env.byte_array_from_slice(&engine.serialize())
        .map(|a| a.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// 0 when the bytes are not an engine this build can read: the caller rebuilds from text.
#[no_mangle]
pub extern "system" fn Java_dev_androidpoet_wren_browser_blocking_rules_NativeEngine_deserialize(
    env: JNIEnv,
    _: JClass,
    bytes: JByteArray,
) -> jlong {
    let Ok(data) = env.convert_byte_array(&bytes) else { return 0 };
    let mut engine = Engine::default();
    match engine.deserialize(&data) {
        Ok(()) => into_handle(engine),
        Err(_) => 0,
    }
}

/// Stand-ins a `$redirect=` rule may name, each as name, aliases (comma-separated), MIME type
/// and base64 body. Must be called before the engine is shared between threads.
#[no_mangle]
pub extern "system" fn Java_dev_androidpoet_wren_browser_blocking_rules_NativeEngine_useResources(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    entries: JObjectArray,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: as in `engine`; the Kotlin side calls this before publishing the handle.
    let engine = unsafe { &mut *(handle as *mut Engine) };
    let count = env.get_array_length(&entries).unwrap_or(0);
    let mut fields = Vec::with_capacity(count as usize);
    for i in 0..count {
        let Ok(item) = env.get_object_array_element(&entries, i) else { continue };
        if let Some(s) = text(&mut env, &JString::from(item)) {
            fields.push(s);
        }
    }
    let resources = fields.chunks_exact(4).map(|f| Resource {
        name: f[0].clone(),
        aliases: f[1].split(',').filter(|a| !a.is_empty()).map(str::to_owned).collect(),
        kind: ResourceType::Mime(MimeType::from(f[2].as_str())),
        content: f[3].clone(),
        dependencies: vec![],
        permission: Default::default(),
    });
    engine.use_resources(resources);
}

/// The verdict for one subresource, packed in a string so the call allocates once: null to let
/// it through, "B" to block, "R" + data URL to serve a stand-in in its place.
#[no_mangle]
pub extern "system" fn Java_dev_androidpoet_wren_browser_blocking_rules_NativeEngine_check(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    url: JString,
    source_url: JString,
    request_type: JString,
) -> jstring {
    let Some(engine) = engine(handle) else { return std::ptr::null_mut() };
    let (Some(url), Some(source), Some(kind)) =
        (text(&mut env, &url), text(&mut env, &source_url), text(&mut env, &request_type))
    else {
        return std::ptr::null_mut();
    };
    let Ok(request) = Request::new(&url, &source, &kind, "GET") else { return std::ptr::null_mut() };
    let result = engine.check_network_request(&request);
    if !result.should_block() {
        return std::ptr::null_mut();
    }
    match result.redirect {
        Some(redirect) => new_string(&mut env, &format!("R{redirect}")),
        None => new_string(&mut env, "B"),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_androidpoet_wren_browser_blocking_rules_NativeEngine_free(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: the Kotlin side frees each handle once, after its last use.
        drop(unsafe { Box::from_raw(handle as *mut Engine) });
    }
}
