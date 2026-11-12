/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

//! The Tor VPN's packet side: tun2proxy reads the VPN interface Android hands over, runs the
//! TCP/IP stack in user space and opens every TCP connection as a SOCKS5 stream on Tor's local
//! port. Names never reach a resolver on the phone: DNS queries are answered from a private
//! pool of fake addresses, and a connection to one of them reaches Tor by name, so the exit
//! relay resolves it. Tor carries no UDP, so other UDP traffic (QUIC, games, calls) fails and
//! apps fall back to TCP or stop.
//!
//! One tunnel runs at a time. `run` blocks the calling thread until `stop` or a fatal error;
//! its runtime is ours, so stopping never takes the process down with it.

use jni::objects::JClass;
use jni::sys::jint;
use jni::JNIEnv;
use std::sync::Mutex;
use std::time::Duration;
use tun2proxy::{ArgDns, Args, CancellationToken, ProxyParameters};

static RUNNING: Mutex<Option<CancellationToken>> = Mutex::new(None);

/// 0 after a clean stop; negative when the tunnel could not start or failed.
#[no_mangle]
pub extern "system" fn Java_dev_androidpoet_wren_browser_tor_TorTunnel_run(
    _env: JNIEnv,
    _: JClass,
    tun_fd: jint,
    socks_port: jint,
    mtu: jint,
) -> jint {
    let Ok(proxy) = ProxyParameters::try_from(format!("socks5://127.0.0.1:{socks_port}").as_str())
    else {
        return -2;
    };
    let token = CancellationToken::new();
    {
        let Ok(mut running) = RUNNING.lock() else { return -3 };
        if running.is_some() {
            return -1;
        }
        *running = Some(token.clone());
    }
    let mut args = Args::default();
    args.proxy(proxy).dns(ArgDns::Virtual).tun_fd(Some(tun_fd)).close_fd_on_drop(true);
    let mtu = mtu.clamp(1280, 9000) as u16;
    args.tcp_mss = Some(mtu - 40);
    args.max_sessions = 1024;
    let code = match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
        Ok(runtime) => {
            let result = runtime.block_on(tun2proxy::general_run_async(args, mtu, false, token));
            runtime.shutdown_timeout(Duration::from_secs(2));
            if result.is_ok() { 0 } else { -4 }
        }
        Err(_) => -5,
    };
    if let Ok(mut running) = RUNNING.lock() {
        running.take();
    }
    code
}

#[no_mangle]
pub extern "system" fn Java_dev_androidpoet_wren_browser_tor_TorTunnel_stop(_env: JNIEnv, _: JClass) {
    if let Ok(running) = RUNNING.lock() {
        if let Some(token) = running.as_ref() {
            token.cancel();
        }
    }
}
