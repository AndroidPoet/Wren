/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.tor

/** One hop of a circuit as Tor's control port names it. */
data class TorRelay(
  val fingerprint: String,
  val nickname: String,
  /** The relay's address from the consensus; null for a bridge, which is not in it. */
  val address: String? = null,
)

data class TorCircuit(val id: String, val relays: List<TorRelay>)

/** One STREAM event: the stream, its new status, its circuit ("0" before it has one), target host. */
data class TorStreamEvent(val id: String, val status: String, val circuit: String, val host: String)

/**
 * Reads the control port's answers about circuits (control-spec sections 4.1.1 and 4.1.2, as
 * returned by GETINFO circuit-status and stream-status). Kept free of the connection so the
 * parsing can be tested against recorded answers.
 */
object TorCircuits {

  /**
   * The ids of the built circuits carrying streams to [host], newest stream first. Once a
   * stream is connected Tor reports its target as the address the exit resolved, so the name a
   * stream was opened for comes from [names] (stream id to host, kept from STREAM events) when
   * known. The match is exact, which is also how `IsolateDestAddr` groups streams.
   */
  fun circuitIdsFor(
    host: String,
    streamStatus: String,
    names: Map<String, String> = emptyMap(),
  ): List<String> {
    val wanted = normalized(host)
    return streamStatus.lineSequence()
      .mapNotNull { line ->
        val fields = line.trim().split(' ')
        if (fields.size < 4) return@mapNotNull null
        val name = names[fields[0]] ?: hostOfTarget(fields[3])
        fields[2].takeIf { it != "0" && normalized(name) == wanted }
      }
      .toList()
      .asReversed()
      .distinct()
  }

  /** A STREAM event's data (`id status circuit target ...`), or null when it is not one. */
  fun streamEvent(data: String): TorStreamEvent? {
    val fields = data.trim().split(' ')
    if (fields.size < 4) return null
    return TorStreamEvent(fields[0], fields[1], fields[2], hostOfTarget(fields[3]))
  }

  private fun hostOfTarget(target: String): String =
    target.substringBeforeLast(':').removePrefix("[").removeSuffix("]")

  private fun normalized(host: String): String = host.lowercase().trimEnd('.')

  /** The relays of circuit [id], first hop first, or null when it is not built. */
  fun pathOf(id: String, circuitStatus: String): List<TorRelay>? {
    val line = circuitStatus.lineSequence()
      .map { it.trim() }
      .firstOrNull { it.startsWith("$id ") } ?: return null
    val fields = line.split(' ')
    if (fields.size < 3 || fields[1] != "BUILT") return null
    return fields[2].split(',').mapNotNull(::relayOf).takeIf { it.isNotEmpty() }
  }

  /** `$FINGERPRINT~nickname` or `$FINGERPRINT=nickname`, as long names are written. */
  internal fun relayOf(longName: String): TorRelay? {
    val name = longName.removePrefix("$")
    val split = name.indexOfFirst { it == '~' || it == '=' }
    val fingerprint = if (split < 0) name else name.substring(0, split)
    if (fingerprint.length != FINGERPRINT_LENGTH || !fingerprint.all {
        it.isLetterOrDigit()
      }
    ) {
      return null
    }
    val nickname = if (split < 0) fingerprint.take(SHORT_NAME) else name.substring(split + 1)
    return TorRelay(fingerprint.uppercase(), nickname)
  }

  /** The address field of a consensus `r` line (GETINFO ns/id/...), or null. */
  fun addressOf(networkStatus: String): String? = networkStatus.lineSequence()
    .map { it.trim().split(' ') }
    .firstOrNull { it.firstOrNull() == "r" && it.size >= R_LINE_FIELDS }
    ?.get(R_LINE_ADDRESS)

  private const val FINGERPRINT_LENGTH = 40
  private const val SHORT_NAME = 8
  private const val R_LINE_FIELDS = 8
  private const val R_LINE_ADDRESS = 6
}
