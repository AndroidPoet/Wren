/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Applies the filter lists' element-hiding rules to the page.
 *
 * Runs at document start with two jobs. The scriptlet table (SCRIPTLET_TABLE, run by the
 * dispatcher from scriptlets.js) goes first, before any page script. Then the app is asked for
 * this host's selectors; they arrive a few milliseconds later and go into a stylesheet, grouped
 * for speed and validated one by one only when a group is refused, because one selector the
 * engine cannot parse would otherwise void every rule beside it. Generic rules keyed by an id
 * or class are asked for as the page reports the ids and classes its DOM holds.
 *
 * The app assembles one closure out of scriptlets.js, the table and this file, and binds
 * DEBUG, CHANNEL (the reply channel, already taken off window) and SCRIPTLET_TABLE around it.
 * Nothing is left on window for a page to find.
 */
(function () {
  'use strict';
  try {
    if (typeof SCRIPTLET_TABLE === 'object' && typeof runScriptlets === 'function') {
      var ran = runScriptlets(SCRIPTLET_TABLE);
      if (DEBUG && ran) console.log('[wren] scriptlets: ' + ran + ' on ' + location.hostname);
    }
  } catch (ignored) {}

  var host = location.hostname;
  if (!host) return;

  var FALLBACK = [
    '.adsbygoogle', 'ins.adsbygoogle', 'iframe[id^="google_ads_iframe"]', 'div[id^="div-gpt-ad"]',
    'div[id^="google_ads_"]', '[data-ad-client]', '[data-ad-slot]',
    'iframe[src*="googlesyndication.com"]', 'iframe[src*="doubleclick.net"]',
    'iframe[src*="amazon-adsystem.com"]', '[id^="taboola-"]', '.trc_related_container',
    '.OUTBRAIN', '.ob-widget'
  ];
  var DECLARATION = '{display:none !important;}';
  var GROUP = 40;
  var MAX_TOKEN = 128;
  var MAX_BATCH = 4000;
  var HELLO_RETRIES = 4;
  var HELLO_RETRY_MS = 750;
  var MUTATION_SETTLE_MS = 150;

  var sheet = null;
  function ensureSheet() {
    if (sheet) return sheet;
    try {
      sheet = new CSSStyleSheet();
      document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
      return sheet;
    } catch (e) {
      return null;
    }
  }

  function insert(selectors) {
    var target = ensureSheet();
    if (!target) return 0;
    var applied = 0;
    for (var i = 0; i < selectors.length; i += GROUP) {
      var group = selectors.slice(i, i + GROUP);
      try {
        target.insertRule(group.join(',') + DECLARATION, target.cssRules.length);
        applied += group.length;
      } catch (e) {
        for (var j = 0; j < group.length; j++) {
          try {
            target.insertRule(group[j] + DECLARATION, target.cssRules.length);
            applied++;
          } catch (ignored) {}
        }
      }
    }
    return applied;
  }

  var excepted = {};
  var seen = {};
  function fresh(selectors) {
    var list = [];
    for (var i = 0; i < selectors.length; i++) {
      var selector = selectors[i];
      if (seen[selector] || excepted[selector]) continue;
      seen[selector] = true;
      list.push(selector);
    }
    return list;
  }

  function applyPage(payload) {
    var x = payload.x || [];
    for (var i = 0; i < x.length; i++) excepted[x[i]] = true;
    var list = fresh((payload.s || []).concat(payload.g || []));
    var applied = insert(list);
    if (DEBUG) console.log('[wren] cosmetic: ' + applied + ' of ' + list.length + ' page selectors applied');
  }

  function applySurvey(payload) {
    var list = fresh(payload.m || []);
    var applied = insert(list);
    if (DEBUG && list.length) console.log('[wren] cosmetic: ' + applied + ' of ' + list.length + ' surveyed selectors applied');
  }

  insert(fresh(FALLBACK));

  var channel = typeof CHANNEL === 'object' ? CHANNEL : null;
  if (!channel) return;

  var ready = false;
  var sentIds = {};
  var sentClasses = {};
  var pendingIds = [];
  var pendingClasses = [];
  var flushTimer = 0;

  function noteId(id) {
    if (!id || id.length > MAX_TOKEN || sentIds[id]) return;
    sentIds[id] = true;
    pendingIds.push(id);
  }

  function noteClasses(value) {
    var parts = value.split(/\s+/);
    for (var i = 0; i < parts.length; i++) {
      var cls = parts[i];
      if (!cls || cls.length > MAX_TOKEN || sentClasses[cls]) continue;
      sentClasses[cls] = true;
      pendingClasses.push(cls);
    }
  }

  function noteElement(el) {
    var id = el.getAttribute('id');
    if (id) noteId(id);
    var cls = el.getAttribute('class');
    if (cls) noteClasses(cls);
  }

  function surveyTree(root) {
    if (!root || root.nodeType !== 1) return;
    noteElement(root);
    var els = root.querySelectorAll('[id],[class]');
    for (var i = 0; i < els.length; i++) noteElement(els[i]);
  }

  function flush() {
    flushTimer = 0;
    if (!ready || (!pendingIds.length && !pendingClasses.length)) return;
    var message = {
      h: host,
      i: pendingIds.splice(0, MAX_BATCH),
      c: pendingClasses.splice(0, MAX_BATCH)
    };
    try { channel.postMessage(JSON.stringify(message)); } catch (ignored) {}
    if (pendingIds.length || pendingClasses.length) schedule(0);
  }

  function schedule(delay) {
    if (flushTimer) return;
    flushTimer = setTimeout(flush, delay);
  }

  function watch() {
    surveyTree(document.documentElement);
    schedule(0);
    try {
      var observer = new MutationObserver(function (records) {
        for (var i = 0; i < records.length; i++) {
          var record = records[i];
          if (record.type === 'attributes') {
            noteElement(record.target);
            continue;
          }
          var added = record.addedNodes;
          for (var j = 0; j < added.length; j++) surveyTree(added[j]);
        }
        schedule(MUTATION_SETTLE_MS);
      });
      observer.observe(document, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['id', 'class']
      });
    } catch (ignored) {}
  }

  channel.onmessage = function (event) {
    try {
      var payload = JSON.parse(event.data);
      if (!payload) return;
      if (payload.m) {
        applySurvey(payload);
        return;
      }
      if (payload.g || payload.s) {
        applyPage(payload);
        if (!ready) {
          ready = true;
          flush();
        }
      }
    } catch (ignored) {}
  };

  var hellos = 0;
  function hello() {
    if (ready || hellos >= HELLO_RETRIES) return;
    hellos++;
    try { channel.postMessage(JSON.stringify({ h: host })); } catch (ignored) {}
    setTimeout(hello, HELLO_RETRY_MS);
  }
  hello();

  // The observer starts now, on the document itself, so ids and classes are reported as the
  // parser inserts them; waiting for DOMContentLoaded showed every generic ad until then.
  // The full survey after parsing catches whatever the observer's batches missed.
  watch();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () {
      surveyTree(document.documentElement);
      schedule(0);
    }, { once: true });
  }
})();
