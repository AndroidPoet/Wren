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

  private val client = object : ServiceWorkerClient() {
    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
      val headers = request.requestHeaders.orEmpty()
      val pageUrl = headers["Referer"] ?: headers["Origin"].orEmpty()
      return contentBlocker.intercept(request, pageUrl)
    }
  }

  /** Covers the default profile's workers. */
  fun install() = install(ServiceWorkerController.getInstance())

  fun install(controller: ServiceWorkerController) {
    if (!installed.add(controller)) return
    controller.setServiceWorkerClient(client)
  }
}
