/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import java.lang.Character.UnicodeScript
import java.net.IDN

/**
 * How an internationalised host is shown in the address bar.
 *
 * The engine reports every host in its wire form, so `例え.jp` arrives as `xn--r8jz45g.jp`. Showing
 * the letters back is the readable choice for the sites that use them, but a label is shown in
 * its letters only where those letters could not pass for another site: one script per label,
 * no invisible or lookalike characters, and no Cyrillic or Greek label made wholly of letters
 * that read as Latin, which is how `аррӏе.com` imitates `apple.com`. Anything else stays
 * punycode, which is ugly and honest. The rules are a conservative cut of Chromium's IDN
 * display policy; a label this refuses is never one Chromium would show.
 */
object IdnDisplay {

  /** [host] with each punycode label decoded where the decoded form is safe to show. */
  fun forDisplay(host: String): String {
    if (!host.contains(ACE_PREFIX, ignoreCase = true)) return host
    val labels = host.split('.')
    val topLevelScript = labels.last().takeIf { it.startsWith(ACE_PREFIX, ignoreCase = true) }
      ?.let { decode(it) }
      ?.let { singleScriptOf(it) }
    return labels.joinToString(".") { label ->
      if (!label.startsWith(ACE_PREFIX, ignoreCase = true)) return@joinToString label
      val decoded = decode(label) ?: return@joinToString label
      if (isSafeToShow(decoded, topLevelScript)) decoded else label
    }
  }

  private fun decode(label: String): String? {
    val decoded = runCatching {
      IDN.toUnicode(label, IDN.USE_STD3_ASCII_RULES or IDN.ALLOW_UNASSIGNED)
    }.getOrNull()
    // toUnicode hands back its input when the label is not valid punycode. Letters newer than
    // its Unicode 3.2 tables are let through to the checks below, which use the current ones.
    return decoded?.takeIf { !it.equals(label, ignoreCase = true) }
  }

  private fun isSafeToShow(label: String, topLevelScript: UnicodeScript?): Boolean {
    val scripts = HashSet<UnicodeScript>()
    val letters = StringBuilder()
    var previousWasLetter = false
    var index = 0
    while (index < label.length) {
      val codePoint = label.codePointAt(index)
      index += Character.charCount(codePoint)
      if (codePoint in DENIED || DENIED_RANGES.any { codePoint in it }) return false
      when (Character.getType(codePoint).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
        Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        -> {
          val script = UnicodeScript.of(codePoint)
          if (script != UnicodeScript.COMMON && script != UnicodeScript.INHERITED) scripts += script
          letters.appendCodePoint(codePoint)
          previousWasLetter = true
          continue
        }
        Character.DECIMAL_DIGIT_NUMBER -> if (codePoint !in '0'.code..'9'.code) return false
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
        -> if (!previousWasLetter) return false
        Character.DASH_PUNCTUATION -> if (codePoint != '-'.code) return false
        else -> return false
      }
      previousWasLetter = false
    }
    if (scripts.isEmpty()) return true
    if (scripts.size > 1 && scripts !in MIXED_SCRIPTS_ALLOWED) return false
    // Combining marks on Latin, Cyrillic and Greek letters have precomposed forms; a mark that
    // arrives separately is there to change how a letter looks, not to spell a word.
    val script = scripts.first()
    if (script in PRECOMPOSED_SCRIPTS && label.any { Character.getType(it).toByte() in MARKS }) {
      return false
    }
    val lookalikes = LATIN_LOOKALIKES[script] ?: return true
    if (topLevelScript == script) return true
    return !letters.all { it in lookalikes }
  }

  private fun singleScriptOf(label: String): UnicodeScript? {
    var found: UnicodeScript? = null
    for (codePoint in label.codePoints()) {
      if (!Character.isLetter(codePoint)) continue
      val script = UnicodeScript.of(codePoint)
      if (script == UnicodeScript.COMMON || script == UnicodeScript.INHERITED) continue
      if (found != null && found != script) return null
      found = script
    }
    return found
  }

  private const val ACE_PREFIX = "xn--"

  private val MARKS = setOf(
    Character.NON_SPACING_MARK,
    Character.COMBINING_SPACING_MARK,
    Character.ENCLOSING_MARK,
  )

  private val PRECOMPOSED_SCRIPTS =
    setOf(UnicodeScript.LATIN, UnicodeScript.CYRILLIC, UnicodeScript.GREEK)

  private val MIXED_SCRIPTS_ALLOWED = setOf(
    setOf(UnicodeScript.HAN, UnicodeScript.HIRAGANA),
    setOf(UnicodeScript.HAN, UnicodeScript.KATAKANA),
    setOf(UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA),
    setOf(UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA),
    setOf(UnicodeScript.HAN, UnicodeScript.HANGUL),
    setOf(UnicodeScript.HAN, UnicodeScript.BOPOMOFO),
  )

  /** Letters whose common glyph is a Latin letter's; a label made only of these is a lookalike. */
  private val LATIN_LOOKALIKES = mapOf(
    UnicodeScript.CYRILLIC to "аысԁеԍһіјӏорԛѕԝхуъьҽпгѵѡ".toSet(),
    UnicodeScript.GREEK to "αεηικνορτυχωϲ".toSet(),
  )

  /**
   * Single characters that imitate ASCII on their own: dotless i, long s, script g and alpha,
   * dotless j, the middle dot, the fraction slash, zero-width and directional controls, the Kana
   * middle dot, the Hebrew gershayim and the combining long solidus; plus the superscript
   * letters, which count as letters but read as small ASCII. Chromium's list is longer; these
   * are the ones that pass the checks above.
   */
  private val DENIED = setOf(
    0x0131, 0x017F, 0x0261, 0x0251, 0x0237, 0x00B7, 0x2044, 0x200B, 0x200C, 0x200D,
    0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2060, 0x2066, 0x2067, 0x2068,
    0x2069, 0xFEFF, 0x30FB, 0x05F4, 0x0338,
  )

  private val DENIED_RANGES = listOf(0x02B0..0x02B8, 0x1D2C..0x1D6A)
}
