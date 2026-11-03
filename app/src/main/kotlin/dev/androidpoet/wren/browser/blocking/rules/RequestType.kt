/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.browser.blocking.rules

/**
 * What a request is for, as filter lists name it. Bit flags, so a rule's type restriction and
 * a request's possible types are single ints compared with one `and`.
 *
 * WebView never states the type of a subresource, so [infer] reads it off the Accept header and
 * the path. Where those are ambiguous (`Accept: *∕*` covers scripts, fetches, fonts and beacons)
 * the result is a mask of everything the request could be, and a rule applies when it names any
 * of them: a rule that blocks a host's scripts also stops its fetches, which is the safer error.
 */
object RequestType {
  const val SCRIPT = 1
  const val IMAGE = 1 shl 1
  const val STYLESHEET = 1 shl 2
  const val XHR = 1 shl 3
  const val SUBDOCUMENT = 1 shl 4
  const val FONT = 1 shl 5
  const val MEDIA = 1 shl 6
  const val OBJECT = 1 shl 7
  const val PING = 1 shl 8
  const val WEBSOCKET = 1 shl 9
  const val OTHER = 1 shl 10
  const val ALL = (1 shl 11) - 1

  /**
   * What an `Accept: *∕*` request without a telling extension may be. Pings are left out on
   * purpose: a beacon looks exactly like a fetch, and `*$ping,third-party` is a real rule that
   * would otherwise stop every third-party API call on the web.
   */
  @PublishedApi
  internal const val ANY_STAR = SCRIPT or XHR or FONT or OTHER

  fun fromOption(name: String): Int? = when (name) {
    "script" -> SCRIPT
    "image" -> IMAGE
    "stylesheet", "css" -> STYLESHEET
    "xmlhttprequest", "xhr" -> XHR
    "subdocument", "frame" -> SUBDOCUMENT
    "font" -> FONT
    "media" -> MEDIA
    "object" -> OBJECT
    "ping", "beacon" -> PING
    "websocket" -> WEBSOCKET
    "other" -> OTHER
    else -> null
  }

  fun infer(url: String, accept: String?): Int = infer(url) { accept }

  /**
   * The path's extension settles most requests on its own, so the Accept header - which WebView
   * hands over as a freshly built map each time it is asked - is only fetched for the rest.
   */
  inline fun infer(url: String, accept: () -> String?): Int {
    val byExtension = extensionMask(url)
    if (byExtension != 0) return byExtension
    val header = accept()
    if (header != null) {
      when {
        header.startsWith("image/") -> return IMAGE
        header.startsWith("text/css") -> return STYLESHEET
        header.startsWith("text/html") -> return SUBDOCUMENT
        header.startsWith("video/") || header.startsWith("audio/") -> return MEDIA
      }
    }
    return if (header == null || header.startsWith("*/*")) ANY_STAR else OTHER
  }

  /** The type the path's extension implies, or 0 when it has none that matters. */
  fun extensionMask(url: String): Int {
    var end = url.length
    for (i in url.indices) {
      val c = url[i]
      if (c == '?' || c == '#') {
        end = i
        break
      }
    }
    var i = end - 1
    while (i >= 0) {
      val c = url[i]
      if (c == '.') {
        val length = end - i - 1
        return if (length in 1..5) maskOf(url, i + 1, length) else 0
      }
      if (c == '/') return 0
      i--
    }
    return 0
  }

  private fun maskOf(url: String, start: Int, length: Int): Int {
    for (i in EXTENSIONS.indices) {
      val extension = EXTENSIONS[i]
      if (extension.length == length &&
        url.regionMatches(start, extension, 0, length, ignoreCase = true)
      ) {
        return EXTENSION_MASKS[i]
      }
    }
    return 0
  }

  private val EXTENSION_TABLE: List<Pair<String, Int>> = listOf(
    "js" to SCRIPT, "mjs" to SCRIPT,
    "css" to STYLESHEET,
    "png" to IMAGE, "jpg" to IMAGE, "jpeg" to IMAGE, "gif" to IMAGE, "webp" to IMAGE,
    "svg" to IMAGE, "ico" to IMAGE, "avif" to IMAGE, "bmp" to IMAGE,
    "woff" to FONT, "woff2" to FONT, "ttf" to FONT, "otf" to FONT, "eot" to FONT,
    "mp4" to MEDIA, "webm" to MEDIA, "m3u8" to MEDIA, "mpd" to MEDIA, "m4s" to MEDIA,
    "ts" to MEDIA, "mp3" to MEDIA, "m4a" to MEDIA, "aac" to MEDIA, "ogg" to MEDIA,
    "flv" to MEDIA,
    "json" to XHR, "xml" to XHR,
    "swf" to OBJECT,
  )
  private val EXTENSIONS: Array<String> = EXTENSION_TABLE.map { it.first }.toTypedArray()
  private val EXTENSION_MASKS: IntArray = EXTENSION_TABLE.map { it.second }.toIntArray()

  /** The extension of the path, ignoring query and fragment; empty when there is none. */
  fun extensionOf(url: String): String {
    var end = url.length
    for (i in url.indices) {
      val c = url[i]
      if (c == '?' || c == '#') {
        end = i
        break
      }
    }
    var i = end - 1
    while (i >= 0) {
      val c = url[i]
      if (c == '.') {
        val length = end - i - 1
        return if (length in 1..5) url.substring(i + 1, end).lowercase() else ""
      }
      if (c == '/') return ""
      i--
    }
    return ""
  }
}
