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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
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
    val url = request.url?.toString() ?: return null
    val context = RequestContext(url)
    // Safest under Tor: a web font is a download the page controls and a probe of what the
    // device renders, and Tor Browser refuses them at this level. Decided before the ad-block
    // switch, which the user may have off.
    if (settings.torSafestActive && isFont(context.url, request)) return BLOCKED_RESPONSE()
    if (!settings.blockAds) return null

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
    val verdict = rules.decide(context, typeMask, thirdParty, pageHost, hostHit, page.rules)
      ?: return null

    // A redirect rule names what to serve instead; a plain block falls back to the host shims.
    verdict.redirect?.let(blocklist::resourceFor)?.let { return resourceResponse(it) }
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

  /**
   * A few pages' worth, not one: two tabs loading at once, or a service worker whose requests
   * name the page by referrer rather than by tab address, would otherwise evict each other on
   * every request.
   */
  private val recentPages = AtomicReferenceArray<PageFacts?>(PAGE_CACHE_SLOTS)
  private val nextSlot = AtomicInteger()

  /**
   * A page makes hundreds of requests and every one of them carries the same page address, so
   * the page-side work - host, exemption, the rules scoped to its domain - is done once and
   * kept until the address or the rule set changes. Requests arrive on several threads; the
   * entry is immutable, so a lost race costs one recomputation and nothing else.
   */
  private fun pageFactsFor(pageUrl: String, rules: RuleEngine): PageFacts {
    for (i in 0 until PAGE_CACHE_SLOTS) {
      val cached = recentPages.get(i) ?: continue
      if (cached.engine === rules && cached.url == pageUrl) return cached
    }
    val host = HostMatcher.hostOf(pageUrl)
    return PageFacts(
      url = pageUrl,
      engine = rules,
      host = host,
      isExempt = host != null && rules.isPageExempt(host),
      rules = rules.pageRulesFor(host),
    ).also { recentPages.set(nextSlot.getAndIncrement() and (PAGE_CACHE_SLOTS - 1), it) }
  }

  private fun isFont(url: String, request: WebResourceRequest): Boolean =
    RequestType.extensionMask(url) == RequestType.FONT ||
      request.requestHeaders?.get("Sec-Fetch-Dest") == "font"

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

  private fun resourceResponse(resource: Blocklist.Resource): WebResourceResponse =
    WebResourceResponse(
      resource.mimeType,
      if (resource.mimeType.startsWith("image/") || resource.mimeType.startsWith("audio/") ||
        resource.mimeType.startsWith("video/")
      ) {
        null
      } else {
        "utf-8"
      },
      200,
      "OK",
      // Scripts and media are fetched cross-origin; without this a page's own CORS check
      // would reject the stand-in and fall back to the behaviour the rule exists to prevent.
      NO_STORE + ("Access-Control-Allow-Origin" to "*"),
      ByteArrayInputStream(blocklist.resourceBytes(resource)),
    )

  /** The answer for a request that must not leave: an empty body, no network. */
  fun blocked(): WebResourceResponse = BLOCKED_RESPONSE()

  private companion object {
    const val PAGE_CACHE_SLOTS = 4
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
