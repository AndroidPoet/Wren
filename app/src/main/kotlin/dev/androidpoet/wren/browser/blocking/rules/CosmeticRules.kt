/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking.rules

import dev.androidpoet.wren.browser.blocking.HostMatcher

/**
 * Element-hiding rules and scriptlet calls from the filter lists, keyed by the sites they apply
 * to. Hiding fills the gaps that blocked requests leave behind; scriptlets neuter the scripts
 * that would otherwise detect the blocker, replay the ad, or fetch it another way.
 *
 * Everything is stored as the raw selector or call text: the page-side script groups, validates
 * and applies them, because only the browser's own style engine knows which selectors it parses.
 */
class CosmeticRules private constructor(
  private val genericLow: Array<String>,
  private val genericById: HashMap<String, Array<String>>,
  private val genericByClass: HashMap<String, Array<String>>,
  private val specific: HashMap<String, Array<String>>,
  private val exceptions: HashMap<String, Array<String>>,
  private val scriptlets: HashMap<String, Array<String>>,
  private val scriptletExceptions: HashMap<String, Array<String>>,
  val size: Int,
) {

  private val genericLowJson: String by lazy { JsonText.array(genericLow.asList()) }

  /** The hiding payload for a page on [host]: `{"g":[...],"s":[...],"x":[...]}`. */
  fun hidingPayloadFor(host: String): String {
    val chain = HostMatcher.candidates(host)
    val own = ArrayList<String>()
    val excepted = ArrayList<String>()
    for (domain in chain) {
      specific[domain]?.let(own::addAll)
      exceptions[domain]?.let(excepted::addAll)
    }
    return buildString(genericLowJson.length + 64 + own.size * 40) {
      append("{\"g\":").append(genericLowJson)
      append(",\"s\":").append(JsonText.array(own))
      append(",\"x\":").append(JsonText.array(excepted))
      append('}')
    }
  }

  /** The generic selectors keyed by one of the [ids] or [classes] a page reports having. */
  fun genericFor(ids: Collection<String>, classes: Collection<String>): List<String> {
    val out = LinkedHashSet<String>()
    for (id in ids) genericById[id.lowercase()]?.let(out::addAll)
    for (cls in classes) genericByClass[cls.lowercase()]?.let(out::addAll)
    return if (out.isEmpty()) emptyList() else out.toList()
  }

  /** The reply to a page survey: `{"m":[...]}`. */
  fun surveyPayloadFor(ids: Collection<String>, classes: Collection<String>): String {
    val matched = genericFor(ids, classes)
    return buildString(16 + matched.size * 40) {
      append("{\"m\":").append(JsonText.array(matched)).append('}')
    }
  }

  val genericKeyedCount: Int get() = genericById.values.sumOf {
    it.size
  } + genericByClass.values.sumOf { it.size }
  val genericLowCount: Int get() = genericLow.size

  /** Scriptlet calls for pages on [host], in list order, minus the ones excepted there. */
  fun scriptletsFor(host: String): List<String> {
    val chain = HostMatcher.candidates(host)
    val calls = ArrayList<String>()
    val excepted = ArrayList<String>()
    for (domain in chain) {
      scriptlets[domain]?.let(calls::addAll)
      scriptletExceptions[domain]?.let(excepted::addAll)
    }
    if (excepted.isEmpty()) return calls
    if (excepted.any { it.isEmpty() }) return emptyList()
    return calls.filter { it !in excepted }
  }

  /**
   * Every scriptlet rule as JSON for the document-start script: `{"d":{domain:[calls]},
   * "x":{domain:[calls]}}`. Scriptlets have to run before the page's own scripts, so the whole
   * table travels with the script rather than being asked for.
   */
  fun scriptletTableJson(): String = buildString {
    append("{\"d\":").append(JsonText.objectOfArrays(scriptlets))
    append(",\"x\":").append(JsonText.objectOfArrays(scriptletExceptions))
    append('}')
  }

  class Builder {
    private val generic = LinkedHashSet<String>()
    private val genericExceptions = HashSet<String>()
    private val specific = HashMap<String, LinkedHashSet<String>>()
    private val exceptions = HashMap<String, LinkedHashSet<String>>()
    private val scriptlets = HashMap<String, LinkedHashSet<String>>()
    private val scriptletExceptions = HashMap<String, LinkedHashSet<String>>()
    private var count = 0

    /** Adds one list line if it is a hiding rule or scriptlet the engine can apply. */
    fun add(line: String): Boolean {
      val separator = separatorIn(line) ?: return false
      val isException = separator.second == "#@#"
      val domains = line.substring(0, separator.first)
      val body = line.substring(separator.first + separator.second.length).trim()
      if (body.isEmpty() || domains.contains('*')) return false
      val included = ArrayList<String>()
      val excluded = ArrayList<String>()
      if (domains.isNotEmpty()) {
        for (entry in domains.split(',')) {
          val domain = entry.trim().lowercase()
          if (domain.isEmpty()) continue
          if (domain.startsWith("~")) excluded.add(domain.substring(1)) else included.add(domain)
        }
      }
      return if (body.startsWith("+js(")) {
        addScriptlet(body, included, excluded, isException)
      } else {
        addSelector(body, included, excluded, isException)
      }
    }

    private fun addSelector(
      selector: String,
      included: List<String>,
      excluded: List<String>,
      isException: Boolean,
    ): Boolean {
      if (!isPlausibleSelector(selector)) return false
      count++
      when {
        isException && included.isEmpty() -> genericExceptions.add(selector)
        isException -> included.forEach {
          exceptions.getOrPut(it) { LinkedHashSet() }.add(selector)
        }
        included.isEmpty() -> {
          generic.add(selector)
          excluded.forEach { exceptions.getOrPut(it) { LinkedHashSet() }.add(selector) }
        }
        else -> {
          included.forEach { specific.getOrPut(it) { LinkedHashSet() }.add(selector) }
          excluded.forEach { exceptions.getOrPut(it) { LinkedHashSet() }.add(selector) }
        }
      }
      return true
    }

    private fun addScriptlet(
      body: String,
      included: List<String>,
      excluded: List<String>,
      isException: Boolean,
    ): Boolean {
      if (!body.endsWith(")")) return false
      val call = body.substring(4, body.length - 1).trim()
      val name = call.substringBefore(',').trim()
      // An empty exception call turns every scriptlet off for the site.
      if (name.isNotEmpty() && name !in SUPPORTED_SCRIPTLETS) return false
      if (included.isEmpty()) return false
      count++
      val target = if (isException) scriptletExceptions else scriptlets
      included.forEach { target.getOrPut(it) { LinkedHashSet() }.add(call) }
      if (!isException) {
        excluded.forEach { scriptletExceptions.getOrPut(it) { LinkedHashSet() }.add(call) }
      }
      return true
    }

    fun build(): CosmeticRules {
      generic.removeAll(genericExceptions)
      val low = ArrayList<String>()
      val byId = HashMap<String, LinkedHashSet<String>>()
      val byClass = HashMap<String, LinkedHashSet<String>>()
      for (selector in generic) {
        val key = genericKeyOf(selector)
        when {
          key == null -> low.add(selector)
          selector[0] == '#' -> byId.getOrPut(key) { LinkedHashSet() }.add(selector)
          else -> byClass.getOrPut(key) { LinkedHashSet() }.add(selector)
        }
      }
      return CosmeticRules(
        genericLow = low.toTypedArray(),
        genericById = byId.toArrays(),
        genericByClass = byClass.toArrays(),
        specific = specific.toArrays(),
        exceptions = exceptions.toArrays(),
        scriptlets = scriptlets.toArrays(),
        scriptletExceptions = scriptletExceptions.toArrays(),
        size = count,
      )
    }

    private fun HashMap<String, LinkedHashSet<String>>.toArrays(): HashMap<String, Array<String>> {
      val result = HashMap<String, Array<String>>(size * 2)
      for ((key, value) in this) result[key] = value.toTypedArray()
      return result
    }
  }

  companion object {
    val EMPTY: CosmeticRules = Builder().build()

    /** Names the bundled scriptlet library implements, aliases included. */
    val SUPPORTED_SCRIPTLETS: Set<String> = hashSetOf(
      "set-constant", "set", "abort-on-property-read", "aopr", "abort-on-property-write",
      "aopw", "abort-current-script", "acs", "abort-current-inline-script", "acis",
      "json-prune", "json-prune-fetch-response", "json-prune-xhr-response",
      "trusted-replace-fetch-response", "trusted-replace-xhr-response", "no-setTimeout-if",
      "nostif", "setTimeout-defuser", "no-setInterval-if", "nosiif", "nano-setTimeout-booster",
      "nano-stb", "nano-setInterval-booster", "nano-sib", "addEventListener-defuser", "aeld",
      "no-window-open-if", "nowoif", "window.open-defuser", "no-fetch-if", "prevent-fetch",
      "no-xhr-if", "prevent-xhr", "remove-attr", "ra", "remove-class", "rc", "remove-node-text",
      "rmnt", "replace-node-text", "rpnt", "noeval", "noeval-if", "nowebrtc", "nobab", "nofab",
      "popads-dummy", "set-cookie", "set-local-storage-item", "set-session-storage-item",
      "remove-cookie", "cookie-remover", "href-sanitizer", "no-cookie-if",
    )

    /** The id or class a generic selector hinges on, lowercased, or null when it has none. */
    fun genericKeyOf(selector: String): String? {
      if (selector.length < 2 || selector.contains(',')) return null
      val marker = selector[0]
      if (marker != '#' && marker != '.') return null
      var end = 1
      while (end < selector.length && isIdentChar(selector[end])) end++
      if (end == 1 || (end < selector.length && selector[end] == '\\')) return null
      return selector.substring(1, end).lowercase()
    }

    private fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-'

    /** True for lines carrying a hiding rule or scriptlet: `domains##selector` and kin. */
    fun isCosmeticLine(line: String): Boolean = separatorIn(line) != null

    private fun separatorIn(line: String): Pair<Int, String>? {
      val hash = line.indexOf('#')
      if (hash < 0) return null
      return when {
        line.startsWith("#@#", hash) -> hash to "#@#"
        line.startsWith("##", hash) -> hash to "##"
        else -> null
      }
    }

    /**
     * A cheap sanity check: a selector the style engine rejects would void every rule grouped
     * with it, so obvious junk stays out and the page script validates the rest.
     */
    fun isPlausibleSelector(selector: String): Boolean {
      if (selector.length > 1000 || selector.contains('{') || selector.contains('}')) return false
      var round = 0
      var square = 0
      for (c in selector) {
        when (c) {
          '(' -> round++
          ')' -> round--
          '[' -> square++
          ']' -> square--
        }
        if (round < 0 || square < 0) return false
      }
      return round == 0 && square == 0
    }
  }
}

/** Just enough JSON writing for the payloads above; no library, no reflection. */
internal object JsonText {
  fun quote(value: String): String {
    val out = StringBuilder(value.length + 8)
    out.append('"')
    for (c in value) {
      when (c) {
        '"' -> out.append("\\\"")
        '\\' -> out.append("\\\\")
        '\n' -> out.append("\\n")
        '\r' -> out.append("\\r")
        '\t' -> out.append("\\t")
        '<' -> out.append("\\u003c")
        '\u2028' -> out.append("\\u2028")
        '\u2029' -> out.append("\\u2029")
        else -> if (c.code < 0x20) {
          out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
        } else {
          out.append(c)
        }
      }
    }
    return out.append('"').toString()
  }

  fun array(values: List<String>): String {
    if (values.isEmpty()) return "[]"
    val out = StringBuilder(values.size * 32)
    out.append('[')
    values.forEachIndexed { i, value ->
      if (i > 0) out.append(',')
      out.append(quote(value))
    }
    return out.append(']').toString()
  }

  fun objectOfArrays(map: Map<String, Array<String>>): String {
    if (map.isEmpty()) return "{}"
    val out = StringBuilder(map.size * 64)
    out.append('{')
    var first = true
    for ((key, values) in map) {
      if (!first) out.append(',')
      first = false
      out.append(quote(key)).append(':').append(array(values.asList()))
    }
    return out.append('}').toString()
  }
}
