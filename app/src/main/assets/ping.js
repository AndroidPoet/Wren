/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Hyperlink auditing. A link's `ping` attribute makes the engine POST to every address it lists
 * the moment the link is followed: a click tracker that needs no script and that no filter list
 * catches, because the request belongs to the link, not to a tracker's domain. Firefox and Tor
 * Browser never send pings; Chromium offers an app no switch. The attribute is removed as the
 * click arrives, before the engine reads it, in every frame this script reaches.
 */
(function () {
  'use strict';

  function strip(event) {
    var path;
    try { path = event.composedPath ? event.composedPath() : null; } catch (ignored) { path = null; }
    if (!path) {
      path = [];
      var node = event.target;
      while (node) { path.push(node); node = node.parentNode; }
    }
    for (var i = 0; i < path.length; i++) {
      var element = path[i];
      try {
        if (element && element.nodeType === 1 && element.hasAttribute('ping')) element.removeAttribute('ping');
      } catch (ignored) {}
    }
  }

  // A middle-click or a modifier-click opens the link too, as auxclick; both are seen first here,
  // in the capture phase on the window, before any handler of the page.
  ['click', 'auxclick'].forEach(function (type) {
    try { window.addEventListener(type, strip, true); } catch (ignored) {}
  });
})();
