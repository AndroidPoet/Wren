/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import android.os.CancellationSignal
import android.webkit.WebView
import androidx.webkit.CustomHeader
import androidx.webkit.OutcomeReceiverCompat
import androidx.webkit.PrefetchException
import androidx.webkit.PrerenderException
import androidx.webkit.PrerenderOperationCallback
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.androidpoet.wren.settings.SettingsStore
import timber.log.Timber
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes the next navigation fast before it is asked for.
 *
 * Layered, cheapest first:
 *   - warm the OS DNS cache, which works on every WebView and saves the DNS round trip;
 *   - warm up the renderer process so the first load is not paying for a cold start;
 *   - preconnect to the likely host, so TCP and TLS are already done at the tap;
 *   - prefetch the whole document, so the bytes are in the cache before Enter.
 *
 * The DNS tier works everywhere; the last three need a WebView recent enough to expose them and
 * are skipped otherwise.
 *
 * Prefetching fetches a page the user has not committed to yet, so it is deliberately
 * conservative: only for an address typed in full or a site already in history - both places
 * where the destination is unambiguous - never for a bare search suggestion, and never in a
 * private tab. It is a single setting the user can turn off.
 *
 * Under Tor none of it runs: the DNS tier resolves through the system resolver, outside the
 * tunnel, and the rest would announce a guess to an exit before the user has chosen it.
 *
 * All of this needs WebView 133+; on anything older the feature checks fail and every call is a
 * no-op, so the browser still works, just without the head start.
 */
@Singleton
class SpeculativeLoader internal constructor(
  private val settings: SettingsStore,
  private val executor: Executor,
  private val resolve: (host: String) -> Unit,
) {

  @Inject
  constructor(settings: SettingsStore) : this(
    settings,
    Executors.newSingleThreadExecutor(),
    { host -> InetAddress.getAllByName(host) },
  )

  /** Speculation is off by setting, and always off under Tor. */
  private val allowed: Boolean get() = settings.preloadPages && !settings.torMode
  private var currentSignal: CancellationSignal? = null
  private var lastUrl: String? = null

  /** The default WebView's profile, or null when the installed WebView is too old to expose it. */
  private val profile: Profile? by lazy {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
      null
    } else {
      runCatching { ProfileStore.getInstance().getProfile(Profile.DEFAULT_PROFILE_NAME) }
        .getOrNull()
    }
  }

  private var logged = false

  private fun logSupportOnce() {
    if (logged) return
    logged = true
    Timber.d(
      "speculative support: multiProfile=%s prefetch=%s preconnect=%s warm=%s profile=%s",
      WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE),
      WebViewFeature.isFeatureSupported(WebViewFeature.PROFILE_URL_PREFETCH),
      WebViewFeature.isFeatureSupported(WebViewFeature.PRECONNECT),
      WebViewFeature.isFeatureSupported(WebViewFeature.WARM_UP_RENDERER_PROCESS),
      profile != null,
    )
  }

  /** Bring the renderer process up before the first page, so a cold start is not on the clock. */
  /**
   * Primes the network stack for the sites this user actually opens: a QUIC hint lets the very
   * first connection race HTTP/3 instead of discovering it via Alt-Svc a visit later, and a
   * queued preconnect has the sockets open before the first keystroke. Also raises the HTTP
   * cache quota - a browser lives on its revisit cache - and caps the prefetch machinery.
   */
  fun primeForSites(urls: List<String>) {
    val profile = profile ?: return
    val origins = urls.map { originOf(it) }.filter { it.isNotBlank() }.distinct()
    if (allowed && origins.isNotEmpty()) {
      if (WebViewFeature.isFeatureSupported(WebViewFeature.ADD_QUIC_HINTS_V1)) {
        runCatching { profile.addQuicHints(origins.toSet()) }
      }
      if (WebViewFeature.isFeatureSupported(WebViewFeature.ENQUEUE_PRECONNECT)) {
        origins.take(PRECONNECT_SITES).forEach { runCatching { profile.enqueuePreconnect(it) } }
      }
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.HTTP_CACHE_MANAGER)) {
      runCatching {
        val cache = profile.getHttpCache()
        if (cache.quotaBytes < HTTP_CACHE_BYTES) cache.setQuotaBytes(HTTP_CACHE_BYTES)
      }
    }
    runCatching {
      profile.prefetchCache.setMaxPrefetches(MAX_PREFETCHES)
      profile.prefetchCache.setPrefetchTtlSeconds(PREFETCH_TTL_SECONDS)
    }
  }

  /**
   * Sends the standard Save-Data request header on every request, or stops sending it. Sites
   * and image CDNs that honour it (a large share of the web) respond with lighter images and
   * leaner payloads - the honest way to trade image quality for speed on a slow connection,
   * decided by the site that knows its own assets.
   */
  fun applyDataSaver(send: Boolean) {
    val profile = profile ?: return
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.CUSTOM_REQUEST_HEADERS)) return
    runCatching {
      if (send) {
        profile.addCustomHeader(CustomHeader(SAVE_DATA_HEADER, "on", setOf("*")))
      } else {
        profile.clearCustomHeader(SAVE_DATA_HEADER)
      }
    }
  }

  private var lastWarmedOrigin: String? = null

  /**
   * Cheap connection warm-up for an origin the user is about to hit: DNS on any WebView, an open
   * socket where preconnect exists. No prefetch - nothing about the page itself is fetched.
   */
  fun warmOrigin(url: String) {
    if (!allowed || !url.startsWith("https://", ignoreCase = true)) return
    val origin = originOf(url)
    if (origin.isBlank() || origin == lastWarmedOrigin) return
    lastWarmedOrigin = origin
    Timber.d("warming origin %s", origin)
    val host = origin.removePrefix("https://")
    // Checked again on the executor: Tor may have come on while the lookup waited its turn.
    executor.execute { if (allowed) runCatching { resolve(host) } }
    val profile = profile ?: return
    if (WebViewFeature.isFeatureSupported(WebViewFeature.PRECONNECT)) {
      runCatching { profile.preconnect(origin) }
    }
  }

  fun warmUpRenderer() {
    logSupportOnce()
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WARM_UP_RENDERER_PROCESS)) return
    runCatching { profile?.warmUpRendererProcess() }
      .onFailure { Timber.d(it, "renderer warm-up unavailable") }
  }

  /**
   * The user is probably about to open [url]. Preconnect always; prefetch the full document when
   * the target is a committed-looking https address.
   */
  fun speculate(url: String, isPrivate: Boolean) {
    // Prefetch and preconnect only speak https, and a private tab must leave no early footprints.
    if (isPrivate || !allowed || !url.startsWith("https://", ignoreCase = true)) {
      return
    }
    if (url == lastUrl) return
    cancel()
    lastUrl = url

    val host = url.removePrefix("https://").substringBefore('/').substringBefore(':')

    // Tier one, works on every WebView: warm the OS DNS cache. Chromium resolves through the
    // system resolver, so a lookup done now is a cache hit when the real navigation happens,
    // saving the DNS round trip - often the slowest single step before the first byte.
    if (host.isNotEmpty()) {
      executor.execute {
        if (!allowed) return@execute
        runCatching { resolve(host) }
          .onSuccess { Timber.d("dns prewarmed %s", host) }
      }
    }

    // Tier two needs a WebView new enough to expose these: preconnect opens the socket and does
    // the TLS handshake ahead of time, and prefetch pulls the whole document into the cache.
    val profile = profile ?: return

    if (WebViewFeature.isFeatureSupported(WebViewFeature.PRECONNECT)) {
      runCatching { profile.preconnect(originOf(url)) }
    }

    if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROFILE_URL_PREFETCH)) return
    val signal = CancellationSignal()
    currentSignal = signal
    runCatching {
      profile.prefetchUrlAsync(
        url,
        signal,
        executor,
        object : OutcomeReceiverCompat<Void, PrefetchException> {
          override fun onResult(result: Void?) {
            Timber.d("prefetched %s", url)
          }

          override fun onError(error: PrefetchException) {
            Timber.d("prefetch of %s failed: %s", url, error.message)
          }
        },
      )
    }.onFailure { Timber.d(it, "could not start a prefetch") }
  }

  /** Drop the in-flight prefetch: the target changed, or the user is no longer choosing one. */
  private var prerenderSignal: CancellationSignal? = null

  /**
   * The strongest tier: render the page in a hidden tab of [webView] right now, so choosing it
   * costs nothing at all. Reserved for a history match the user is visibly typing toward -
   * prerender runs the page's JavaScript, which is too much speculation for anything less
   * certain than a place the user already goes.
   */
  fun prerender(webView: WebView, url: String) {
    if (!allowed || !url.startsWith("https://", ignoreCase = true)) return
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.PRERENDER_WITH_URL)) return
    prerenderSignal?.cancel()
    val signal = CancellationSignal()
    prerenderSignal = signal
    runCatching {
      WebViewCompat.prerenderUrlAsync(
        webView,
        url,
        signal,
        executor,
        object : PrerenderOperationCallback {
          override fun onPrerenderActivated() {
            Timber.d("prerender activated for %s", url)
          }

          override fun onError(error: PrerenderException) {
            Timber.d("prerender of %s failed: %s", url, error.message)
          }
        },
      )
    }.onFailure { Timber.d(it, "could not start a prerender") }
  }

  /**
   * Startup warm-up beyond sockets: pull the most likely first destinations whole into the
   * prefetch cache. Only on an unmetered network - speculation spends the user's data.
   */
  fun prefetchLikelyPages(urls: List<String>) {
    if (!allowed) return
    val profile = profile ?: return
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROFILE_URL_PREFETCH)) return
    urls.take(STARTUP_PREFETCHES).forEach { url ->
      if (!url.startsWith("https://", ignoreCase = true)) return@forEach
      runCatching {
        profile.prefetchUrlAsync(
          url,
          CancellationSignal(),
          executor,
          object : OutcomeReceiverCompat<Void, PrefetchException> {
            override fun onResult(result: Void?) {
              Timber.d("startup prefetch %s", url)
            }

            override fun onError(error: PrefetchException) = Unit
          },
        )
      }
    }
  }

  fun cancel() {
    currentSignal?.cancel()
    currentSignal = null
    prerenderSignal?.cancel()
    prerenderSignal = null
    lastUrl = null
  }

  private fun originOf(url: String): String {
    val host = url.removePrefix("https://").substringBefore('/')
    return "https://$host"
  }

  private companion object {
    const val SAVE_DATA_HEADER = "Save-Data"
    const val PRECONNECT_SITES = 3
    const val STARTUP_PREFETCHES = 2
    const val HTTP_CACHE_BYTES = 256L * 1024 * 1024
    const val MAX_PREFETCHES = 10
    const val PREFETCH_TTL_SECONDS = 300
  }
}
