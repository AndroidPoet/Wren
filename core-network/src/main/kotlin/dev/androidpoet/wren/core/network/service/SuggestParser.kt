/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.network.service

import com.squareup.moshi.Moshi
import dev.androidpoet.wren.core.model.SearchEngine.SuggestFormat

/**
 * Reads the two autocomplete shapes in the wild. Tolerant by design: a malformed or surprising
 * document yields no suggestions, never an exception on the keystroke path.
 */
object SuggestParser {
  private val anyAdapter = Moshi.Builder().build().adapter(Any::class.java)

  fun parse(format: SuggestFormat, body: String): List<String> {
    val root = runCatching {
      anyAdapter.fromJson(
        body,
      )
    }.getOrNull() as? List<*> ?: return emptyList()
    val phrases = when (format) {
      SuggestFormat.PHRASE_OBJECTS -> root.mapNotNull {
        (it as? Map<*, *>)?.get(
          "phrase",
        ) as? String
      }
      SuggestFormat.OPENSEARCH -> (
        root.getOrNull(
          1,
        ) as? List<*>
        ).orEmpty().mapNotNull { it as? String }
    }
    return phrases.map(String::trim).filter(String::isNotEmpty).distinct()
  }
}
