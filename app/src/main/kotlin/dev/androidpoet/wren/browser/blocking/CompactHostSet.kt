/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * A blocked-host set as one sorted array of 64-bit hashes: eight bytes per host, no objects.
 *
 * The trie this replaces spent 13 MB of heap on 78k hosts because every label was a HashMap
 * node; this holds the same list in 350 KB. A lookup hashes each suffix of the host that
 * starts on a label boundary and binary-searches it, so `stats.g.doubleclick.net` is found via
 * `doubleclick.net` in four probes and `notdoubleclick.net` never reaches it. The hash is FNV-1a
 * over UTF-16 code units, matching `tools/compile_hosts.py`, which writes the bundled file.
 */
class CompactHostSet private constructor(private val hashes: LongArray) {

  val size: Int get() = hashes.size

  /** True when [host] itself or any parent domain of it is in the set. */
  fun matchesSuffixOf(host: String): Boolean {
    if (hashes.isEmpty() || host.isEmpty()) return false
    var start = host.length
    while (true) {
      start = host.lastIndexOf('.', start - 1) + 1
      if (start < host.length && hashes.binarySearch(hashOf(host, start)) >= 0) return true
      if (start == 0) return false
      start -= 1
    }
  }

  fun write(output: OutputStream) {
    val out = DataOutputStream(output)
    out.write(MAGIC)
    out.writeByte(VERSION)
    out.writeInt(hashes.size)
    val buffer = ByteBuffer.allocate(hashes.size * Long.SIZE_BYTES)
    buffer.asLongBuffer().put(hashes)
    out.write(buffer.array())
    out.flush()
  }

  companion object {
    private val MAGIC = "BLZH".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L

    val EMPTY = CompactHostSet(LongArray(0))

    fun of(hosts: Iterable<String>): CompactHostSet = fromHashes(
      hosts.mapNotNull { host -> host.takeIf { it.isNotEmpty() }?.let { hashOf(it, 0) } }
        .toLongArray(),
    )

    /** Reads a file written by [write] or by the compiler script. */
    fun load(input: InputStream): CompactHostSet {
      val data = DataInputStream(input)
      val magic = ByteArray(MAGIC.size).also(data::readFully)
      require(magic.contentEquals(MAGIC)) { "not a host set" }
      require(data.readUnsignedByte() == VERSION) { "unknown host set version" }
      val count = data.readInt()
      require(count in 0..MAX_ENTRIES) { "implausible host count $count" }
      val bytes = ByteArray(count * Long.SIZE_BYTES).also(data::readFully)
      val hashes = LongArray(count)
      ByteBuffer.wrap(bytes).asLongBuffer().get(hashes)
      return CompactHostSet(hashes)
    }

    /** One set holding the entries of both, for merging the starter list into the bundled one. */
    fun union(first: CompactHostSet, second: CompactHostSet): CompactHostSet =
      fromHashes(first.hashes + second.hashes)

    private fun fromHashes(raw: LongArray): CompactHostSet {
      raw.sort()
      var unique = 0
      for (i in raw.indices) {
        if (i == 0 || raw[i] != raw[i - 1]) raw[unique++] = raw[i]
      }
      return CompactHostSet(raw.copyOf(unique))
    }

    private fun hashOf(host: String, start: Int): Long {
      var hash = FNV_OFFSET
      for (i in start until host.length) {
        hash = (hash xor host[i].code.toLong()) * FNV_PRIME
      }
      return hash
    }

    private const val MAX_ENTRIES = 4_000_000
  }
}
