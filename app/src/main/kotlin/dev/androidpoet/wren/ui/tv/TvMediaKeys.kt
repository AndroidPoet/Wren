/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.androidpoet.wren.ui.tv

import android.view.KeyEvent

/**
 * A remote's transport keys never reach a page's video: the engine does not hand them to it. Each
 * key becomes a short script that drives the page's own media element instead. The element is the
 * one in fullscreen, else the one that is playing, else the largest on the page.
 *
 * A video that has never played will not start from a script, because the engine wants a gesture
 * first. For that one case the script answers with the point at the middle of the video, and the
 * caller presses it.
 */
object TvMediaKeys {
  private const val SEEK_SECONDS = 10
  private const val TAP_PREFIX = "tap:"

  fun scriptFor(keyCode: Int): String? = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> script("toggle")
    KeyEvent.KEYCODE_MEDIA_PLAY -> script("play")
    KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> script("pause")
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> script("forward")
    KeyEvent.KEYCODE_MEDIA_REWIND -> script("rewind")
    else -> null
  }

  /**
   * Reads the script's answer, as `evaluateJavascript` hands it back: `"tap:x,y"` in CSS pixels of
   * the visible page, or nothing when the script dealt with the key itself.
   */
  fun tapPoint(result: String?): Pair<Float, Float>? {
    val point = result?.trim('"')?.removePrefix(TAP_PREFIX)?.takeIf { it != result.trim('"') }
    val (x, y) = point?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 2 }
      ?: return null
    return x to y
  }

  private fun script(action: String): String = """
    (function (action, step) {
      function collect(doc, out) {
        try {
          doc.querySelectorAll('video, audio').forEach(function (m) { out.push(m); });
          doc.querySelectorAll('iframe').forEach(function (f) {
            try { if (f.contentDocument) collect(f.contentDocument, out); } catch (e) {}
          });
        } catch (e) {}
        return out;
      }
      function area(m) { var r = m.getBoundingClientRect(); return r.width * r.height; }
      var all = collect(document, []);
      if (!all.length) return '';
      var full = document.fullscreenElement;
      var media = all.filter(function (m) { return full && (m === full || full.contains(m)); })[0] ||
        all.filter(function (m) { return !m.paused; })[0] ||
        all.sort(function (a, b) { return area(b) - area(a); })[0];
      var play = function () { var p = media.play(); if (p && p.catch) p.catch(function () {}); };
      function middle() {
        if (media.ownerDocument !== document || media.tagName !== 'VIDEO') return '';
        function locate() {
          var view = window.visualViewport;
          var left = view ? view.offsetLeft : 0, top = view ? view.offsetTop : 0;
          var width = view ? view.width : innerWidth, height = view ? view.height : innerHeight;
          var box = media.getBoundingClientRect();
          var x = box.left + box.width / 2 - left, y = box.top + box.height / 2 - top;
          var seen = box.width > 0 && box.height > 0 && x >= 0 && y >= 0 && x <= width && y <= height;
          return { x: x, y: y, seen: seen };
        }
        var at = locate();
        if (!at.seen) {
          media.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
          at = locate();
        }
        return at.seen ? 'tap:' + at.x + ',' + at.y : '';
      }
      if (action === 'toggle' || action === 'play') {
        if (!media.paused) { if (action === 'toggle') media.pause(); return ''; }
        var point = media.played.length === 0 ? middle() : '';
        if (point) return point;
        play();
      }
      if (action === 'pause') media.pause();
      if (action === 'forward') media.currentTime = Math.min(media.duration || Infinity, media.currentTime + step);
      if (action === 'rewind') media.currentTime = Math.max(0, media.currentTime - step);
      return '';
    })('$action', $SEEK_SECONDS);
  """.trimIndent()
}
