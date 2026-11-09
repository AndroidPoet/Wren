/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking

import android.app.Application
import dev.androidpoet.wren.browser.blocking.rules.BlockEngine
import dev.androidpoet.wren.browser.blocking.rules.CosmeticRules
import dev.androidpoet.wren.browser.blocking.rules.EngineBuilder
import dev.androidpoet.wren.browser.blocking.rules.RuleEngine
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The blocker's working set: the bundled snapshot of the default filter lists, replaced whole
 * by [FilterListManager] once fresher copies are on disk, plus the shims that stand in for a
 * few scripts.
 *
 * Everything is immutable once built and swapped by reference, so the interception thread
 * never takes a lock.
 */
@Singleton
class Blocklist @Inject constructor(
  private val application: Application,
) {

  @Volatile
  private var snapshot: BlockEngine? = null

  @Volatile
  private var installed: BlockEngine? = null

  /**
   * The snapshot compiled into the APK by `tools/compile_hosts.py` and `compile_rules.py`.
   * Held only until fresher lists replace it: two full engines would double the heap.
   */
  val bundled: BlockEngine
    get() = snapshot?.takeIf { it.version == BUNDLED_VERSION } ?: synchronized(this) {
      snapshot?.takeIf { it.version == BUNDLED_VERSION } ?: run {
        // The compiled host set is read in a few milliseconds and already answers for most ad
        // and tracker requests, so it goes in first; the text rules follow when parsed, and
        // until then only a cold start's first page waits for nothing.
        val hosts = snapshot?.hosts ?: loadCompiledHosts()
        if (snapshot == null) {
          snapshot = BlockEngine(hosts, RuleEngine.EMPTY, CosmeticRules.EMPTY, HOSTS_ONLY_VERSION)
          notifyEngineChanged()
        }
        loadBundled(hosts).also { full ->
          // Fresher lists may have landed meanwhile; two full engines would double the heap.
          if (installed == null) {
            snapshot = full
            notifyEngineChanged()
          }
        }
      }
    }

  private val engineListeners = CopyOnWriteArrayList<(BlockEngine) -> Unit>()
  private val firstEngine = CountDownLatch(1)

  /** The engine in use if one is built yet, else null. Never blocks. */
  val engineOrNull: BlockEngine? get() = installed ?: snapshot

  /** Calls [listener] now if an engine exists, and on the building thread after every swap. */
  fun addEngineListener(listener: (BlockEngine) -> Unit) {
    engineListeners += listener
    engineOrNull?.let(listener)
  }

  private fun notifyEngineChanged() {
    val current = engineOrNull ?: return
    firstEngine.countDown()
    for (listener in engineListeners) {
      runCatching { listener(current) }
        .onFailure { Timber.w(it, "engine listener failed") }
    }
  }

  /**
   * Scripts that break the page when they simply vanish. Rather than serve nothing, serve a
   * stub that defines the globals the page expects - the approach Firefox ships as SmartBlock.
   */
  private val shims: Map<String, String> = mapOf(
    "google-analytics.com" to "shims/analytics.js",
    "googletagmanager.com" to "shims/analytics.js",
    "googlesyndication.com" to "shims/gpt.js",
    "doubleclick.net" to "shims/gpt.js",
  )

  /** Shim bodies are read from the APK once; serving one must not cost an asset open per ad. */
  private val shimBodies = ConcurrentHashMap<String, ByteArray>()

  /**
   * What the blocker consults right now. A request that arrives before any engine exists waits
   * briefly, then goes through unfiltered: it runs on one of WebView's own worker threads, and
   * parking those, or compiling lists on one of them, stalls the page far worse than a few
   * unblocked requests during the first half second after a cold start.
   */
  val engine: BlockEngine
    get() = engineOrNull ?: run {
      firstEngine.await(FIRST_ENGINE_WAIT_MS, TimeUnit.MILLISECONDS)
      engineOrNull ?: BlockEngine.EMPTY.also {
        if (!failOpenLogged) {
          failOpenLogged = true
          Timber.w("a request was answered before any filter engine was ready")
        }
      }
    }

  @Volatile
  private var failOpenLogged = false

  val rules: RuleEngine get() = engine.rules

  val cosmetics: CosmeticRules get() = engine.cosmetics

  val size: Int get() = engine.size

  /** Builds the bundled set now, on the caller's background thread, so no page request pays. */
  fun preload() {
    bundled
    // The party check needs the suffix list; it is small and loads alongside the host set.
    PublicSuffixes.load(application.assets)
  }

  fun isBlocked(host: String): Boolean = engine.hosts.matchesSuffixOf(host)

  fun install(engine: BlockEngine, replacesSnapshot: Boolean) {
    synchronized(this) {
      installed = engine
      if (replacesSnapshot) snapshot = null
    }
    notifyEngineChanged()
    Timber.i(
      "filter engine v%d active: %d hosts, %d rules, %d cosmetic",
      engine.version,
      engine.hosts.size,
      engine.rules.size,
      engine.cosmetics.size,
    )
  }

  /** The shim asset for [host], or null when there is nothing worth standing in for. */
  fun shimAssetFor(host: String): String? =
    HostMatcher.candidates(host).firstNotNullOfOrNull { shims[it] }

  fun shimBytes(assetPath: String): ByteArray = shimBodies.getOrPut(assetPath) {
    withoutLicenseHeader(application.assets.open(assetPath).use { it.readBytes() })
  }

  /**
   * A page can read what it is served, and the licence comment at the top of a script names
   * the app's author; the page gets the code alone. Other files start with their own magic
   * bytes and pass through untouched.
   */
  private fun withoutLicenseHeader(bytes: ByteArray): ByteArray {
    if (bytes.size < 4 || bytes[0] != '/'.code.toByte() || bytes[1] != '*'.code.toByte()) {
      return bytes
    }
    val text = String(bytes, Charsets.UTF_8)
    val end = text.indexOf("*/")
    if (end < 0) return bytes
    return text.substring(end + 2).trimStart().toByteArray(Charsets.UTF_8)
  }

  /** A stand-in resource and its MIME type, by the name a `$redirect=` rule uses. */
  class Resource(val assetPath: String, val mimeType: String)

  /**
   * The resources `$redirect=` rules name that Wren can serve: inert scripts, empty text,
   * transparent pixels, silent media and the two Google loaders the shims already stand in
   * for. A name not listed here still blocks the request; it just serves nothing.
   */
  fun resourceFor(name: String): Resource? = RESOURCES[name]

  fun resourceBytes(resource: Resource): ByteArray = shimBytes(resource.assetPath)

  /** The compiled host set alone, for a rebuild that already parses the bundled text lists. */
  fun bundledHosts(): CompactHostSet = snapshot?.hosts ?: loadCompiledHosts()

  private fun loadCompiledHosts(): CompactHostSet = runCatching {
    application.assets.open(COMPILED_ASSET).use(CompactHostSet::load)
  }.onFailure { Timber.w(it, "could not read the compiled host set") }
    .getOrDefault(CompactHostSet.EMPTY)

  private fun loadBundled(hosts: CompactHostSet): BlockEngine {
    val started = System.nanoTime()
    val phases = StringBuilder()
    var mark = started
    fun lap(label: String) {
      val now = System.nanoTime()
      phases.append(label).append('=').append((now - mark) / 1_000_000).append("ms ")
      mark = now
    }
    val builder = EngineBuilder().addHostSet(hosts)
    lap("hosts")
    for (asset in listOf(STARTER_ASSET, RULES_ASSET, COSMETIC_ASSET)) {
      runCatching {
        application.assets.open(asset).bufferedReader().useLines { builder.addLines(it) }
      }.onFailure { Timber.w(it, "could not read bundled %s", asset) }
      lap(asset)
    }
    return builder.build(version = BUNDLED_VERSION).also {
      lap("build")
      Timber.i("bundled phases: %s", phases)
      val elapsedMs = (System.nanoTime() - started) / 1_000_000
      Timber.i(
        "bundled filters loaded: %d hosts, %d rules, %d cosmetic in %d ms",
        it.hosts.size,
        it.rules.size,
        it.cosmetics.size,
        elapsedMs,
      )
    }
  }

  private companion object {
    const val FIRST_ENGINE_WAIT_MS = 500L

    /** Engine versions below the first downloaded build: the host set alone, then the full snapshot. */
    const val HOSTS_ONLY_VERSION = -1
    const val BUNDLED_VERSION = 0

    val RESOURCES: Map<String, Resource> = buildMap {
      fun put(resource: Resource, vararg names: String) = names.forEach { this[it] = resource }
      put(Resource("resources/noop.js", "application/javascript"), "noopjs", "noop.js")
      put(Resource("resources/noop.txt", "text/plain"), "nooptext", "noop.txt")
      put(Resource("resources/noop.html", "text/html"), "noopframe", "noop.html")
      put(Resource("resources/noop.css", "text/css"), "noopcss", "noop.css")
      put(Resource("resources/noop.json", "application/json"), "noopjson", "noop.json")
      put(Resource("resources/1x1.gif", "image/gif"), "1x1.gif", "1x1-transparent.gif")
      put(Resource("resources/2x2.png", "image/png"), "2x2.png", "2x2-transparent.png")
      put(Resource("resources/3x2.png", "image/png"), "3x2.png", "3x2-transparent.png")
      put(Resource("resources/32x32.png", "image/png"), "32x32.png", "32x32-transparent.png")
      put(Resource("resources/noop-0.1s.mp3", "audio/mpeg"), "noopmp3-0.1s", "noop-0.1s.mp3")
      put(Resource("resources/noop-1s.mp4", "video/mp4"), "noopmp4-1s", "noop-1s.mp4")
      put(
        Resource("shims/gpt.js", "application/javascript"),
        "googletagservices_gpt.js",
        "googletagservices.com/gpt.js",
        "googlesyndication_adsbygoogle.js",
        "googlesyndication.com/adsbygoogle.js",
      )
      put(
        Resource("shims/analytics.js", "application/javascript"),
        "google-analytics_analytics.js",
        "google-analytics.com/analytics.js",
        "google-analytics_ga.js",
        "google-analytics.com/ga.js",
        "googletagmanager_gtm.js",
        "googletagmanager.com/gtm.js",
      )
    }

    const val COMPILED_ASSET = "hosts.bin"
    const val STARTER_ASSET = "blocklist.txt"
    const val RULES_ASSET = "rules.txt"
    const val COSMETIC_ASSET = "cosmetic.txt"
  }
}
