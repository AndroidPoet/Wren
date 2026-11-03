/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser

import dev.androidpoet.wren.core.model.Suggestion
import java.net.URLEncoder

/**
 * DuckDuckGo-style "!bang" shortcuts: `!w einstein` goes straight to Wikipedia's search for
 * einstein, skipping the search engine entirely.
 *
 * Everything here is resolved on the device against a fixed table, so a bang never leaks to the
 * search engine the way it would if the whole query were sent off and redirected server-side.
 *
 * Only *known* bangs trigger. An unfamiliar `!foo` is left in the query untouched, so a stray
 * exclamation mark never hijacks a search.
 */
object Bangs {

  /** [name] labels the destination; [template] takes the URL-encoded query; [home] has none. */
  data class Bang(val name: String, val template: String, val home: String)

  private val BANGS: Map<String, Bang> = buildMap {
    fun add(vararg triggers: String, name: String, template: String, home: String) {
      triggers.forEach { put(it, Bang(name, template, home)) }
    }

    add(
      "w",
      "wiki",
      name = "Wikipedia",
      template = "https://en.wikipedia.org/w/index.php?search=%s",
      home = "https://en.wikipedia.org/",
    )
    add(
      "yt",
      name = "YouTube",
      template = "https://m.youtube.com/results?search_query=%s",
      home = "https://m.youtube.com/",
    )
    add(
      "gh",
      "github",
      name = "GitHub",
      template = "https://github.com/search?q=%s&type=repositories",
      home = "https://github.com/",
    )
    add(
      "so",
      name = "Stack Overflow",
      template = "https://stackoverflow.com/search?q=%s",
      home = "https://stackoverflow.com/",
    )
    add(
      "r",
      "reddit",
      name = "Reddit",
      template = "https://www.reddit.com/search/?q=%s",
      home = "https://www.reddit.com/",
    )
    add(
      "a",
      "amazon",
      name = "Amazon",
      template = "https://www.amazon.com/s?k=%s",
      home = "https://www.amazon.com/",
    )
    add(
      "g",
      "google",
      name = "Google",
      template = "https://www.google.com/search?q=%s",
      home = "https://www.google.com/",
    )
    add(
      "ddg",
      name = "DuckDuckGo",
      template = "https://duckduckgo.com/?q=%s",
      home = "https://duckduckgo.com/",
    )
    add(
      "b",
      "bing",
      name = "Bing",
      template = "https://www.bing.com/search?q=%s",
      home = "https://www.bing.com/",
    )
    add(
      "sp",
      name = "Startpage",
      template = "https://www.startpage.com/sp/search?query=%s",
      home = "https://www.startpage.com/",
    )
    add(
      "br",
      name = "Brave Search",
      template = "https://search.brave.com/search?q=%s",
      home = "https://search.brave.com/",
    )
    add(
      "maps",
      "osm",
      name = "OpenStreetMap",
      template = "https://www.openstreetmap.org/search?query=%s",
      home = "https://www.openstreetmap.org/",
    )
    add(
      "gmap",
      "gmaps",
      name = "Google Maps",
      template = "https://www.google.com/maps?q=%s",
      home = "https://www.google.com/maps",
    )
    add(
      "gi",
      "images",
      name = "Google Images",
      template = "https://www.google.com/search?tbm=isch&q=%s",
      home = "https://images.google.com/",
    )
    add(
      "tr",
      "translate",
      name = "Google Translate",
      template = "https://translate.google.com/?op=translate&text=%s",
      home = "https://translate.google.com/",
    )
    add(
      "wa",
      name = "Wolfram Alpha",
      template = "https://www.wolframalpha.com/input?i=%s",
      home = "https://www.wolframalpha.com/",
    )
    add(
      "mdn",
      name = "MDN",
      template = "https://developer.mozilla.org/en-US/search?q=%s",
      home = "https://developer.mozilla.org/",
    )
    add(
      "npm",
      name = "npm",
      template = "https://www.npmjs.com/search?q=%s",
      home = "https://www.npmjs.com/",
    )
    add(
      "imdb",
      name = "IMDb",
      template = "https://www.imdb.com/find/?q=%s",
      home = "https://www.imdb.com/",
    )
    add(
      "play",
      "gplay",
      name = "Google Play",
      template = "https://play.google.com/store/search?q=%s&c=apps",
      home = "https://play.google.com/store/apps",
    )
    add(
      "x",
      "tw",
      "twitter",
      name = "X",
      template = "https://twitter.com/search?q=%s",
      home = "https://twitter.com/",
    )
    add(
      "gm",
      "gmail",
      name = "Gmail",
      template = "https://mail.google.com/mail/u/0/#search/%s",
      home = "https://mail.google.com/",
    )
    add(
      "gd",
      "gdrive",
      name = "Google Drive",
      template = "https://drive.google.com/drive/search?q=%s",
      home = "https://drive.google.com/",
    )
    add(
      "yh",
      "yahoo",
      name = "Yahoo",
      template = "https://search.yahoo.com/search?p=%s",
      home = "https://www.yahoo.com/",
    )
    add(
      "kt",
      "kotlin",
      name = "Kotlin docs",
      template = "https://kotlinlang.org/docs/home.html?q=%s",
      home = "https://kotlinlang.org/",
    )
    add(
      "adev",
      "android",
      name = "Android Developers",
      template = "https://developer.android.com/s/results?q=%s",
      home = "https://developer.android.com/",
    )
    add(
      "archwiki",
      "aw",
      name = "Arch Wiki",
      template = "https://wiki.archlinux.org/index.php?search=%s",
      home = "https://wiki.archlinux.org/",
    )
    add(
      "hn",
      name = "Hacker News",
      template = "https://hn.algolia.com/?q=%s",
      home = "https://news.ycombinator.com/",
    )
    add(
      "gs",
      "scholar",
      name = "Google Scholar",
      template = "https://scholar.google.com/scholar?q=%s",
      home = "https://scholar.google.com/",
    )
    add(
      "yts",
      "yt-music",
      "ytm",
      name = "YouTube Music",
      template = "https://music.youtube.com/search?q=%s",
      home = "https://music.youtube.com/",
    )
    add(
      "sc",
      "soundcloud",
      name = "SoundCloud",
      template = "https://soundcloud.com/search?q=%s",
      home = "https://soundcloud.com/",
    )
    add(
      "gif",
      "giphy",
      name = "GIPHY",
      template = "https://giphy.com/search/%s",
      home = "https://giphy.com/",
    )
    add(
      "d",
      "dict",
      name = "Dictionary",
      template = "https://www.dictionary.com/browse/%s",
      home = "https://www.dictionary.com/",
    )
    add(
      "up",
      "unsplash",
      name = "Unsplash",
      template = "https://unsplash.com/s/photos/%s",
      home = "https://unsplash.com/",
    )
  }

  private val LOWER = BANGS.mapKeys { it.key.lowercase() }

  /**
   * The site URL for the first known bang in [input], with the rest of the words as its query, or
   * null when there is no known bang and the caller should fall back to a normal search.
   */
  fun resolve(input: String): String? {
    val tokens = input.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null

    var bang: Bang? = null
    val queryTokens = mutableListOf<String>()
    for (token in tokens) {
      val candidate = if (bang == null && token.length > 1 && token.startsWith("!")) {
        LOWER[token.substring(1).lowercase()]
      } else {
        null
      }
      if (candidate != null) bang = candidate else queryTokens.add(token)
    }

    val resolved = bang ?: return null
    val query = queryTokens.joinToString(" ")
    return if (query.isBlank()) resolved.home else resolved.template.format(encode(query))
  }

  /** True while the user is still typing the bang token itself, e.g. `!w` before any space. */
  fun isTypingBang(text: String): Boolean {
    val trimmed = text.trimStart()
    return trimmed.startsWith("!") && !trimmed.contains(' ')
  }

  /** Local, private completions for a partly typed bang. Never contacts the network. */
  fun suggestions(text: String): List<Suggestion> {
    val prefix = text.trimStart().removePrefix("!").lowercase()
    return LOWER.entries
      .asSequence()
      .filter { it.key.startsWith(prefix) }
      .sortedWith(compareBy({ it.key.length }, { it.key }))
      .distinctBy { it.value.name }
      .take(MAX_SUGGESTIONS)
      .map { (trigger, bang) ->
        Suggestion(phrase = "!$trigger  ·  ${bang.name}", completion = "!$trigger ")
      }
      .toList()
  }

  private fun encode(query: String): String = URLEncoder.encode(query, "UTF-8")

  private val WHITESPACE = Regex("\\s+")
  private const val MAX_SUGGESTIONS = 8
}
