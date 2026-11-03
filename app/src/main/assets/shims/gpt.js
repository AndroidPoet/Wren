/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Stands in for the Google Publisher Tag and AdSense loader.
 *
 * Publisher pages build their layout around googletag: they queue commands, define slots, and
 * frequently wait on display() before revealing article content. If googletag never appears the
 * queued functions never run and the article stays hidden, which looks like the browser broke
 * the site. This runs the queue immediately and answers with inert slot objects.
 */
(function () {
  'use strict';

  function noop() {}

  function chainable(target) {
    // Slot builders are used fluently: defineSlot(...).addService(...).setTargeting(...).
    var handler = function () {
      return target;
    };
    [
      'addService', 'setTargeting', 'clearTargeting', 'setCollapseEmptyDiv',
      'setForceSafeFrame', 'defineSizeMapping', 'setClickUrl', 'setSafeFrameConfig',
    ].forEach(function (name) {
      target[name] = handler;
    });
    target.getSlotElementId = function () {
      return '';
    };
    target.getAdUnitPath = function () {
      return '';
    };
    target.getTargeting = function () {
      return [];
    };
    target.getTargetingKeys = function () {
      return [];
    };
    return target;
  }

  var googletag = window.googletag || {};
  var queue = googletag.cmd || [];

  googletag.apiReady = true;
  googletag.pubadsReady = true;

  googletag.defineSlot = function () {
    return chainable({});
  };
  googletag.defineOutOfPageSlot = googletag.defineSlot;
  googletag.destroySlots = noop;
  googletag.display = noop;
  googletag.enableServices = noop;
  googletag.disablePublisherConsole = noop;
  googletag.sizeMapping = function () {
    var builder = {};
    builder.addSize = function () {
      return builder;
    };
    builder.build = function () {
      return [];
    };
    return builder;
  };
  googletag.pubads = function () {
    return chainable({
      enableSingleRequest: noop,
      enableLazyLoad: noop,
      disableInitialLoad: noop,
      collapseEmptyDivs: noop,
      refresh: noop,
      addEventListener: noop,
      removeEventListener: noop,
      getSlots: function () {
        return [];
      },
      setPrivacySettings: noop,
      setRequestNonPersonalizedAds: noop,
    });
  };
  googletag.companionAds = googletag.pubads;
  googletag.content = function () {
    return { setContent: noop };
  };

  // cmd normally queues until GPT loads; here it runs at once so nothing waits forever.
  googletag.cmd = {
    push: function (fn) {
      try {
        if (typeof fn === 'function') fn();
      } catch (ignored) {
        // A publisher callback throwing is the page's problem, not ours to propagate.
      }
      return 1;
    },
  };

  window.googletag = googletag;

  // Drain anything queued before this stub arrived.
  for (var i = 0; i < queue.length; i++) googletag.cmd.push(queue[i]);

  // AdSense pages push config objects onto this array and read its length back.
  window.adsbygoogle = window.adsbygoogle || [];
  if (typeof window.adsbygoogle.push !== 'function') {
    window.adsbygoogle.push = function () {
      return 1;
    };
  }
})();
