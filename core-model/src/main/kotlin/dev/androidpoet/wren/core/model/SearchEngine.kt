/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.core.model

/**
 * Search engines Wren can hand a query to. All defaults are engines that do not build a profile
 * from the query; [GOOGLE] is offered only as an escape hatch.
 */
enum class SearchEngine(
  val label: String,
  val queryTemplate: String,
  val homeUrl: String,
  /**
   * Where keystrokes go for autocomplete: the engine's own endpoint, so what a person types is
   * seen by the search engine they chose and nobody else.
   */
  val suggestTemplate: String,
  val suggestFormat: SuggestFormat,
) {
  DUCKDUCKGO(
    "DuckDuckGo",
    "https://duckduckgo.com/?q=%s",
    "https://duckduckgo.com/",
    // kl=wt-wt asks for no region, so no locale is disclosed.
    "https://duckduckgo.com/ac/?q=%s&kl=wt-wt",
    SuggestFormat.PHRASE_OBJECTS,
  ),
  STARTPAGE(
    "Startpage",
    "https://www.startpage.com/sp/search?query=%s",
    "https://www.startpage.com/",
    "https://www.startpage.com/osuggestions?q=%s",
    SuggestFormat.OPENSEARCH,
  ),
  BRAVE(
    "Brave Search",
    "https://search.brave.com/search?q=%s",
    "https://search.brave.com/",
    "https://search.brave.com/api/suggest?q=%s",
    SuggestFormat.OPENSEARCH,
  ),
  MOJEEK(
    "Mojeek",
    "https://www.mojeek.com/search?q=%s",
    "https://www.mojeek.com/",
    "https://www.mojeek.com/complete/?q=%s",
    SuggestFormat.OPENSEARCH,
  ),
  WIKIPEDIA(
    "Wikipedia",
    "https://en.wikipedia.org/w/index.php?search=%s",
    "https://en.wikipedia.org/",
    "https://en.wikipedia.org/w/api.php?action=opensearch&format=json&search=%s",
    SuggestFormat.OPENSEARCH,
  ),
  GOOGLE(
    "Google",
    "https://www.google.com/search?q=%s",
    "https://www.google.com/",
    "https://suggestqueries.google.com/complete/search?client=firefox&q=%s",
    SuggestFormat.OPENSEARCH,
  ),
  ;

  /** The shape of an autocomplete response. */
  enum class SuggestFormat {
    /** `[{"phrase": "..."}]`, DuckDuckGo's own. */
    PHRASE_OBJECTS,

    /** `["query", ["...", "..."]]`, the OpenSearch suggestions convention most engines speak. */
    OPENSEARCH,
  }

  companion object {
    val DEFAULT: SearchEngine = DUCKDUCKGO

    fun fromNameOrDefault(name: String?): SearchEngine =
      entries.firstOrNull { it.name == name } ?: DEFAULT
  }
}
