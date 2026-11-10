/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking.rules

import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Brave's adblock-rust, the network half of the filter engine: it reads EasyList and uBlock
 * syntax as uBlock Origin does (on adblock-rust's own uBO test corpus of 221,451 recorded
 * requests it agrees with uBO on 99.985%), including `removeparam`, regex rules and the
 * first-party reach of a plain `||host^` rule, which the Kotlin [RuleEngine] does not cover.
 *
 * A handle is asked from WebView's request threads while a list refresh may replace it, so
 * every call counts itself in and [close] frees the native engine only after the last call in
 * flight has left.
 */
class NativeEngine private constructor(private val handle: Long) {

  private val users = AtomicInteger(1)
  private val closed = AtomicBoolean(false)

  /** The verdict for a subresource: null lets it through. */
  fun check(url: String, sourceUrl: String, typeMask: Int): Verdict? = use {
    val single = TYPE_NAMES[typeMask]
    if (single != null) return@use parse(check(handle, url, sourceUrl, single))
    // An address with no telling extension or header may be any of these; a rule for one of
    // them decides, as a type mask does in the Kotlin engine.
    for ((mask, name) in CANDIDATES) {
      if (typeMask and mask == 0) continue
      parse(check(handle, url, sourceUrl, name))?.let { return@use it }
    }
    null
  }

  fun serialize(): ByteArray? = use { serialize(handle) }

  /** Frees the engine once nothing is using it any more. Safe to call more than once. */
  fun close() {
    if (closed.compareAndSet(false, true)) release()
  }

  private inline fun <T> use(block: () -> T): T? {
    while (true) {
      val count = users.get()
      if (count == 0) return null
      if (users.compareAndSet(count, count + 1)) break
    }
    try {
      return block()
    } finally {
      release()
    }
  }

  private fun release() {
    if (users.decrementAndGet() == 0) free(handle)
  }

  sealed interface Verdict {
    data object Block : Verdict

    /** A stand-in to serve in place of the request, as a `data:` URL. */
    data class Redirect(val dataUrl: String) : Verdict
  }

  /**
   * Collects list text for [build] the way the Kotlin builder reads it: uBlock's `!#if`
   * conditions are settled here (adblock-rust keeps every branch), and hosts-file entries and
   * bare domains become `||host^` rules, so a custom list may mix the formats line by line.
   */
  class ListText {
    private val text = StringBuilder()
    private val conditions = ArrayList<Boolean>()

    fun add(raw: String) {
      val line = raw.trimSpaces()
      if (line.startsWith("!#")) {
        when {
          line.startsWith("!#if ") -> conditions.add(EngineBuilder.evaluate(line.substring(5)))
          line.startsWith("!#else") -> if (conditions.isNotEmpty()) {
            conditions[conditions.lastIndex] = !conditions.last()
          }
          line.startsWith("!#endif") -> if (conditions.isNotEmpty()) {
            conditions.removeAt(conditions.lastIndex)
          }
        }
        return
      }
      if (line.isEmpty() || line[0] == '!' || line[0] == '[' || !conditions.all { it }) return
      val host = if (line.startsWith("||")) null else EngineBuilder.hostsFileEntry(line)
      if (host != null) {
        text.append("||").append(host).append("^\n")
      } else if (line[0] != '#' || CosmeticRules.isCosmeticLine(line)) {
        text.append(line).append('\n')
      }
    }

    /** Ends the list: a condition left open by a truncated download does not leak into the next. */
    fun endList() = conditions.clear()

    fun build(resources: List<Resource>): NativeEngine? =
      build(listOf(text.toString()), listOf(false), resources)
  }

  /** One `$redirect=` stand-in: its name, other names, MIME type and base64 body. */
  class Resource(val name: String, val aliases: List<String>, val mime: String, val base64: String)

  companion object {
    /** False when the native library could not be loaded: the Kotlin engine then decides alone. */
    val isAvailable: Boolean by lazy {
      runCatching { System.loadLibrary("wren_adblock") }
        .onFailure { Timber.w(it, "native filter engine unavailable") }
        .isSuccess
    }

    /** Builds an engine from list texts; [isHosts] marks each list that is a hosts file. */
    fun build(
      lists: List<String>,
      isHosts: List<Boolean>,
      resources: List<Resource>,
    ): NativeEngine? {
      if (!isAvailable) return null
      val flags = ByteArray(isHosts.size) { if (isHosts[it]) 1 else 0 }
      val handle = create(lists.toTypedArray(), flags)
      return withResources(handle, resources)
    }

    /** Reads an engine back from [serialize]; null when the bytes are from another build. */
    fun restore(bytes: ByteArray, resources: List<Resource>): NativeEngine? {
      if (!isAvailable) return null
      return withResources(deserialize(bytes), resources)
    }

    private fun withResources(handle: Long, resources: List<Resource>): NativeEngine? {
      if (handle == 0L) return null
      val fields = ArrayList<String>(resources.size * 4)
      for (r in resources) {
        fields += r.name
        fields += r.aliases.joinToString(",")
        fields += r.mime
        fields += r.base64
      }
      useResources(handle, fields.toTypedArray())
      return NativeEngine(handle)
    }

    private fun parse(answer: String?): Verdict? = when {
      answer == null -> null
      answer == "B" -> Verdict.Block
      answer.startsWith("R") -> Verdict.Redirect(answer.substring(1))
      else -> null
    }

    private val TYPE_NAMES: Map<Int, String> = mapOf(
      RequestType.SCRIPT to "script",
      RequestType.IMAGE to "image",
      RequestType.STYLESHEET to "stylesheet",
      RequestType.XHR to "xmlhttprequest",
      RequestType.SUBDOCUMENT to "sub_frame",
      RequestType.FONT to "font",
      RequestType.MEDIA to "media",
      RequestType.OBJECT to "object",
      RequestType.PING to "ping",
      RequestType.WEBSOCKET to "websocket",
      RequestType.OTHER to "other",
    )

    private val CANDIDATES: List<Pair<Int, String>> = TYPE_NAMES.entries.map { it.key to it.value }

    @JvmStatic private external fun create(lists: Array<String>, hostsFlags: ByteArray): Long

    @JvmStatic private external fun serialize(handle: Long): ByteArray?

    @JvmStatic private external fun deserialize(bytes: ByteArray): Long

    @JvmStatic private external fun useResources(handle: Long, entries: Array<String>)

    @JvmStatic
    private external fun check(handle: Long, url: String, source: String, type: String): String?

    @JvmStatic private external fun free(handle: Long)
  }
}
