/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.tor

import timber.log.Timber

/**
 * The Tor VPN's packet engine in native/ (tun2proxy): a user-space TCP/IP stack that turns the
 * VPN interface's TCP connections into SOCKS5 streams on Tor's port, and answers DNS from a pool
 * of stand-in addresses so names are resolved by the exit relay, never on the phone.
 */
internal object TorTunnel {

  val isAvailable: Boolean by lazy {
    runCatching { System.loadLibrary("wren_adblock") }
      .onFailure { Timber.w(it, "tunnel library unavailable") }
      .isSuccess
  }

  /**
   * Blocks until [stop] or a fatal error. Takes ownership of [tunFd] and closes it on return.
   * 0 after a clean stop, negative when the tunnel could not run (-1: one is already running).
   */
  @JvmStatic external fun run(tunFd: Int, socksPort: Int, mtu: Int): Int

  /** Asks a running tunnel to end; [run] returns shortly after. */
  @JvmStatic external fun stop()
}
