/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import dev.androidpoet.wren.browser.blocking.rules.PageRules
import dev.androidpoet.wren.browser.blocking.rules.RequestContext
import dev.androidpoet.wren.browser.blocking.rules.RequestType
import dev.androidpoet.wren.browser.blocking.rules.RuleEngine
import dev.androidpoet.wren.settings.SettingsStore
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides what to do with each subresource a page asks for.
 *
 * Called from [android.webkit.WebViewClient.shouldInterceptRequest], which runs on a background
 * thread for every single request on the page, so this path stays allocation-light: one hash
 * lookup for the host, then only the handful of pattern rules filed under the address's tokens.
 */
@Singleton
class ContentBlocker @Inject constructor(
  private val blocklist: Blocklist,
  private val settings: SettingsStore,
) {

  /** null means "let it through". */
  fun intercept(request: WebResourceRequest, pageUrl: String): WebResourceResponse? {
    // Never touch the document itself: blocking it would break navigation rather than an ad.
    if (request.isForMainFrame) return null
    if (!settings.blockAds) return null

    val url = request.url?.toString() ?: return null
    val context = RequestContext(url)
    val requestHost = context.host
    if (requestHost.isEmpty()) return null
    val rules = blocklist.rules
    val page = pageFactsFor(pageUrl, rules)
    val pageHost = page.host

    // The user can exempt a whole site when blocking breaks it, and a list can do the same.
    if (pageHost != null && (page.isExempt || settings.isAllowlisted(pageHost))) return null

    // The host set is a list of trackers, so a site loading its own resources never hits it;
    // pattern rules know about paths and decide first-party requests for themselves.
    val thirdParty = pageHost == null || !HostMatcher.isSameParty(requestHost, pageHost)
    val hostHit = thirdParty && blocklist.isBlocked(requestHost)
    val typeMask = RequestType.infer(context.url) { acceptHeaderOf(request) }
    val blocked = rules.shouldBlock(context, typeMask, thirdParty, pageHost, hostHit, page.rules)
    if (!blocked) return null

    val shimAsset = blocklist.shimAssetFor(requestHost)
    return if (shimAsset != null) shimResponse(shimAsset) else BLOCKED_RESPONSE()
  }

  /** What a page's requests share, worked out when the first of them arrives. */
  private class PageFacts(
    val url: String,
    val engine: RuleEngine,
    val host: String?,
    val isExempt: Boolean,
    val rules: PageRules,
  )

  @Volatile
  private var lastPage: PageFacts? = null

  /**
   * A page makes hundreds of requests and every one of them carries the same page address, so
   * the page-side work - host, exemption, the rules scoped to its domain - is done once and
   * kept until the address or the rule set changes. Requests arrive on several threads; the
   * entry is immutable, so a lost race costs one recomputation and nothing else.
   */
  private fun pageFactsFor(pageUrl: String, rules: RuleEngine): PageFacts {
    val cached = lastPage
    if (cached != null && cached.engine === rules && cached.url == pageUrl) return cached
    val host = HostMatcher.hostOf(pageUrl)
    return PageFacts(
      url = pageUrl,
      engine = rules,
      host = host,
      isExempt = host != null && rules.isPageExempt(host),
      rules = rules.pageRulesFor(host),
    ).also { lastPage = it }
  }

  private fun acceptHeaderOf(request: WebResourceRequest): String? {
    val headers = request.requestHeaders ?: return null
    return headers["Accept"] ?: headers["accept"]
  }

  private fun shimResponse(assetPath: String): WebResourceResponse = WebResourceResponse(
    "application/javascript",
    "utf-8",
    200,
    "OK",
    NO_STORE,
    ByteArrayInputStream(blocklist.shimBytes(assetPath)),
  )

  private companion object {
    val NO_STORE = mapOf("Cache-Control" to "no-store")

    /**
     * A 403 with an empty body, the nearest a WebView interception can get to the network
     * error a desktop blocker produces. An empty 200 was tried first and turned out worse: a
     * script that expected JSON got an empty success, failed to parse it, and asked again, and
     * one player did that sixteen times a second for as long as the page stayed open.
     */
    val BLOCKED_RESPONSE = {
      WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked",
        NO_STORE,
        ByteArrayInputStream(ByteArray(0)),
      )
    }
  }
}
