/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.tor

import android.content.res.AssetManager

/** How Tor reaches its first relay when the network in front of it blocks Tor. */
enum class BridgeMode {
  /** Straight to a public guard relay. */
  NONE,

  /** The obfs4 bridges Tor Browser ships; traffic looks like nothing in particular. */
  OBFS4,

  /** Snowflake: a WebRTC hop through a volunteer's browser, hard to block wholesale. */
  SNOWFLAKE,

  /** meek: Tor inside TLS to a large CDN, the most expensive but the hardest to block. */
  MEEK,

  /** Lines the user obtained from bridges.torproject.org or the Telegram bot. */
  CUSTOM,
  ;

  val usesBuiltIn: Boolean get() = this == OBFS4 || this == SNOWFLAKE || this == MEEK
}

/**
 * One validated `Bridge` line. Tor reads its configuration as plain text, so a line is only
 * ever rebuilt from parsed, checked parts: a pasted value cannot smuggle a newline and a
 * second option (`Log`, `SocksPort`, `UseBridges 0`) into the torrc behind it.
 */
data class BridgeLine(
  val transport: String?,
  val address: String,
  val fingerprint: String?,
  val arguments: List<Pair<String, String>>,
) {
  /** The line as torrc wants it after the `Bridge` keyword. */
  fun render(): String = buildString {
    transport?.let { append(it).append(' ') }
    append(address)
    fingerprint?.let { append(' ').append(it) }
    arguments.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
  }
}

object Bridges {
  /**
   * The transports the bundled lyrebird serves and Tor Browser still ships. obfs2, obfs3 and
   * scramblesuit are retired: lyrebird still speaks them, but a line naming one is a mistake,
   * not a bridge.
   */
  val TRANSPORTS: Set<String> = setOf("obfs4", "snowflake", "webtunnel", "meek_lite")

  private const val MAX_LINES = 50
  private const val MAX_LINE_LENGTH = 2048

  private val transportName = Regex("""[a-z0-9_]{1,32}""")
  private val ipv4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
  private val hostLabel = """[A-Za-z0-9]([A-Za-z0-9-]{0,62}[A-Za-z0-9])?"""
  private val hostName = Regex("""$hostLabel(\.$hostLabel)*""")
  private val ipv6 = Regex("""[0-9A-Fa-f:.]{2,45}""")
  private val fingerprintHex = Regex("""[0-9A-Fa-f]{40}""")
  private val argumentKey = Regex("""[A-Za-z0-9_-]{1,32}""")

  /** Everything Tor Browser's own lines use, and nothing torrc could read as structure. */
  private val argumentValue = Regex("""[A-Za-z0-9+/=._:%,\-]{1,1024}""")

  /**
   * Parses one bridge line as a user or bridges.torproject.org writes it:
   * `[transport] address:port [fingerprint] [key=value ...]`. A leading `Bridge ` keyword and
   * a `$` before the fingerprint are accepted and dropped.
   *
   * @throws IllegalArgumentException naming what is wrong, in words fit for a dialog.
   */
  fun parse(line: String): BridgeLine {
    require(line.length <= MAX_LINE_LENGTH) { "line is too long" }
    require(line.none { it.isISOControl() }) { "line contains control characters" }
    val tokens = line.trim().split(' ', '\t').filter { it.isNotEmpty() }.toMutableList()
    if (tokens.firstOrNull().equals("bridge", ignoreCase = true)) tokens.removeAt(0)
    require(tokens.isNotEmpty()) { "line is empty" }

    val transport = tokens.first().takeIf { ':' !in it }?.also {
      require(transportName.matches(it)) { "transport name '$it' is not valid" }
      require(it in TRANSPORTS) { "transport '$it' is not supported" }
      tokens.removeAt(0)
    }
    require(tokens.isNotEmpty()) { "address is missing" }
    val address = parseAddress(tokens.removeAt(0))

    val fingerprint = tokens.firstOrNull()?.takeIf { '=' !in it }?.let {
      val hex = it.removePrefix("$")
      require(fingerprintHex.matches(hex)) { "fingerprint '$it' is not 40 hex digits" }
      tokens.removeAt(0)
      hex.uppercase()
    }
    require(transport != null || fingerprint != null) { "a plain bridge needs its fingerprint" }

    val arguments = tokens.map { token ->
      val key = token.substringBefore('=', "")
      val value = token.substringAfter('=', "")
      require(argumentKey.matches(key)) { "'$token' is not a key=value argument" }
      require(argumentValue.matches(value)) {
        "the value of '$key' has characters a bridge line cannot take"
      }

      key to value
    }
    require(transport != null || arguments.isEmpty()) { "a plain bridge takes no arguments" }
    return BridgeLine(transport, address, fingerprint, arguments)
  }

  /**
   * Parses pasted text: one bridge per line, blank lines and `#` comments skipped. Fails on
   * the first bad line so the user sees which one.
   */
  fun parseAll(text: String): List<BridgeLine> {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    require(lines.isNotEmpty()) { "no bridge lines" }
    require(lines.size <= MAX_LINES) { "more than $MAX_LINES bridge lines" }
    return lines.mapIndexed { index, line ->
      try {
        parse(line)
      } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("line ${index + 1}: ${e.message}", e)
      }
    }
  }

  /** The lines Tor Browser ships for [mode], read from `assets/bridges.txt`. */
  fun builtIn(assets: AssetManager, mode: BridgeMode): List<BridgeLine> {
    val section = when (mode) {
      BridgeMode.OBFS4 -> "obfs4"
      BridgeMode.SNOWFLAKE -> "snowflake"
      BridgeMode.MEEK -> "meek"
      BridgeMode.NONE, BridgeMode.CUSTOM -> return emptyList()
    }
    val text = assets.open(BUILT_IN_ASSET).bufferedReader().use { it.readText() }
    return sectionOf(text, section).let(::parseAll)
  }

  /** The lines under `[name]` in a sectioned bridges file. */
  internal fun sectionOf(text: String, name: String): String {
    var inSection = false
    return text.lines().filter { line ->
      val trimmed = line.trim()
      if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        inSection = trimmed.substring(1, trimmed.length - 1) == name
        false
      } else {
        inSection
      }
    }.joinToString("\n")
  }

  /**
   * The torrc options for [lines]: bridges on and each line. A transport also needs
   * [transportPlugin], which is set over the control port once Tor is up rather than written
   * here: tor-android runs `--verify-config` and the real client in one process, and a
   * `ClientTransportPlugin ... exec` line seen by both trips an assertion in tor (its count of
   * unconfigured proxies survives the first run). Until the plugin is set, `DisableNetwork`
   * keeps Tor from reaching for the bridges, or anything else, without it.
   */
  fun torrcOptions(lines: List<BridgeLine>): String {
    if (lines.isEmpty()) return ""
    return buildString {
      append("UseBridges 1\n")
      if (lines.any { it.transport != null }) append("DisableNetwork 1\n")
      lines.forEach { append("Bridge ").append(it.render()).append('\n') }
    }
  }

  /**
   * The `ClientTransportPlugin` value for the transports [lines] use, or null when they are
   * plain bridges. The plugin path is the only part not built from validated tokens; it comes
   * from the installer, never from the user.
   */
  fun transportPlugin(lines: List<BridgeLine>, pluggableTransportBinary: String): String? {
    val transports = lines.mapNotNull { it.transport }.distinct().sorted()
    if (transports.isEmpty()) return null
    return transports.joinToString(",") + " exec " + pluggableTransportBinary
  }

  private fun parseAddress(token: String): String {
    val host: String
    val portText: String
    if (token.startsWith("[")) {
      val end = token.indexOf(']')
      require(end > 1 && token.getOrNull(end + 1) == ':') { "address '$token' is not [ipv6]:port" }
      host = token.substring(1, end)
      require(ipv6.matches(host) && host.count { it == ':' } >= 2) { "'$host' is not IPv6" }
      portText = token.substring(end + 2)
    } else {
      require(token.count { it == ':' } == 1) { "address '$token' is not host:port" }
      host = token.substringBefore(':')
      portText = token.substringAfter(':')
      val v4 = ipv4.matchEntire(host)
      if (v4 != null) {
        require(v4.groupValues.drop(1).all { it.toInt() in 0..255 }) { "'$host' is not IPv4" }
      } else {
        require(host.length <= 253 && hostName.matches(host)) { "'$host' is not a host name" }
      }
    }
    val port = portText.toIntOrNull()
    require(port != null && port in 1..65535) { "port '$portText' is not valid" }
    return if (token.startsWith("[")) "[$host]:$port" else "$host:$port"
  }

  const val BUILT_IN_ASSET = "bridges.txt"
}
