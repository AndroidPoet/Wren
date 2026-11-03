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
    get() = snapshot ?: synchronized(this) {
      snapshot ?: loadBundled().also {
        snapshot = it
        notifyEngineChanged()
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

  /** What the blocker consults right now; waits briefly for the first engine being built. */
  val engine: BlockEngine
    get() = engineOrNull ?: run {
      firstEngine.await(FIRST_ENGINE_WAIT_MS, TimeUnit.MILLISECONDS)
      engineOrNull ?: bundled
    }

  val rules: RuleEngine get() = engine.rules

  val cosmetics: CosmeticRules get() = engine.cosmetics

  val size: Int get() = engine.size

  /** Builds the bundled set now, on the caller's background thread, so no page request pays. */
  fun preload() {
    bundled
  }

  fun isBlocked(host: String): Boolean = engine.hosts.matchesSuffixOf(host)

  fun install(engine: BlockEngine, replacesSnapshot: Boolean) {
    installed = engine
    if (replacesSnapshot) snapshot = null
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
    application.assets.open(assetPath).use { it.readBytes() }
  }

  /** The compiled host set alone, for a rebuild that already parses the bundled text lists. */
  fun bundledHosts(): CompactHostSet = snapshot?.hosts ?: loadCompiledHosts()

  private fun loadCompiledHosts(): CompactHostSet = runCatching {
    application.assets.open(COMPILED_ASSET).use(CompactHostSet::load)
  }.onFailure { Timber.w(it, "could not read the compiled host set") }
    .getOrDefault(CompactHostSet.EMPTY)

  private fun loadBundled(): BlockEngine {
    val started = System.nanoTime()
    val builder = EngineBuilder().addHostSet(loadCompiledHosts())
    for (asset in listOf(STARTER_ASSET, RULES_ASSET, COSMETIC_ASSET)) {
      runCatching {
        application.assets.open(asset).bufferedReader().useLines { builder.addLines(it) }
      }.onFailure { Timber.w(it, "could not read bundled %s", asset) }
    }
    return builder.build(version = 0).also {
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
    const val FIRST_ENGINE_WAIT_MS = 6_000L
    const val COMPILED_ASSET = "hosts.bin"
    const val STARTER_ASSET = "blocklist.txt"
    const val RULES_ASSET = "rules.txt"
    const val COSMETIC_ASSET = "cosmetic.txt"
  }
}
