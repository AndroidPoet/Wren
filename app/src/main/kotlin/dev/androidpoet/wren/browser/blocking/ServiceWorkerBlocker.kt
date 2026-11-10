/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking

import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the blocker over requests that a page's service worker makes.
 *
 * Those never pass through a WebView's own client: a site that registers a worker can fetch ad
 * scripts and beacons from it with nothing in the way. The worker has no tab, so the referrer
 * the browser attaches is the closest thing to a page URL; without one, nothing is same-party.
 * Every profile has its own controller, so the private profile is wired separately.
 */
@Singleton
class ServiceWorkerBlocker @Inject constructor(
  private val contentBlocker: ContentBlocker,
) {

  private val installed = HashSet<ServiceWorkerController>()

  /**
   * [allowed] is asked before every worker request. A Tor profile's worker that outlives the
   * route (Tor switched off while the profile could not yet be deleted) would otherwise fetch
   * from the real address with the Tor session's cookies; it is answered with a block instead.
   */
  private fun client(allowed: () -> Boolean) = object : ServiceWorkerClient() {
    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
      if (!allowed()) return contentBlocker.blocked()
      val headers = request.requestHeaders.orEmpty()
      val pageUrl = headers["Referer"] ?: headers["Origin"].orEmpty()
      // The local-network fence covers workers too, or an internet page's worker could scan
      // the router and the phone's ports where the page itself is refused. A request that
      // names no page of its own is treated as an internet page's.
      val target = request.url?.toString().orEmpty()
      if (LocalNetwork.isCrossing(
          pageUrl.ifEmpty { NO_PAGE },
          target,
        )
      ) {
        return LocalNetwork.refusal()
      }
      return contentBlocker.intercept(request, pageUrl)
    }
  }

  private companion object {
    /** Stands in for a worker request with no Referer or Origin: never on the local network. */
    const val NO_PAGE = "https://worker.invalid/"
  }

  /** Covers the default profile's workers. */
  fun install(controller: ServiceWorkerController, allowed: () -> Boolean) {
    if (!installed.add(controller)) return
    controller.setServiceWorkerClient(client(allowed))
  }
}
