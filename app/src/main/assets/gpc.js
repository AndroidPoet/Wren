/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The script-visible half of the privacy signals. The `Sec-GPC` and `DNT` headers travel only
 * on the requests the app starts itself; a page's own code reads these properties instead, and
 * consent platforms honour `globalPrivacyControl` under the laws that recognise it.
 */
(function () {
  'use strict';
  function expose(name, value) {
    try {
      Object.defineProperty(Navigator.prototype, name, {
        get: function () { return value; },
        configurable: true,
        enumerable: true,
      });
    } catch (ignored) {}
  }
  expose('globalPrivacyControl', true);
  expose('doNotTrack', '1');
})();
