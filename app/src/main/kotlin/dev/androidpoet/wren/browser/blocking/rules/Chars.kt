/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking.rules

/*
 * ASCII fast paths for list parsing. Android answers Character.isLetterOrDigit, isWhitespace
 * and their kin through ICU, one native call per character; over a few million characters
 * of filter text that alone turned a cold start into a many-second wait. Filter lists are
 * ASCII almost to the byte, so the common case never leaves the JIT.
 */

internal fun Char.isWordChar(): Boolean = when (this) {
  in 'a'..'z', in 'A'..'Z', in '0'..'9' -> true
  else -> code >= 128 && isLetterOrDigit()
}

internal fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

internal fun Char.isSpace(): Boolean = when (this) {
  ' ', '\t', '\r', '\n' -> true
  else -> (code < 32 || code >= 128) && isWhitespace()
}

/** [String.trim] without a native call per character. */
internal fun String.trimSpaces(): String {
  var start = 0
  var end = length
  while (start < end && this[start].isSpace()) start++
  while (end > start && this[end - 1].isSpace()) end--
  return if (start == 0 && end == length) this else substring(start, end)
}
