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
  // A site the user has exempted gets neither scriptlets nor hiding, whatever page this view
  // showed when the script was installed: the decision is made per document, here.
  try {
    if (typeof EXEMPT_HOSTS === 'object' && EXEMPT_HOSTS && EXEMPT_HOSTS.length) {
      var here = location.hostname;
      for (var e = 0; e < EXEMPT_HOSTS.length; e++) {
        var exempt = EXEMPT_HOSTS[e];
        if (here === exempt || (here.length > exempt.length &&
            here.charAt(here.length - exempt.length - 1) === '.' &&
            here.slice(-exempt.length) === exempt)) {
          return;
        }
      }
    }
  } catch (ignored) {}
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
  // What the sheet holds, and which of it is generic, so the page's answer can take back a
  // fallback selector its lists except (`#@#`) or all generic hiding (`$generichide`).
  var kept = [];
  var generic = {};
  var noGeneric = false;
  var disabled = false;
  function fresh(selectors, isGeneric) {
    var list = [];
    if (disabled || (isGeneric && noGeneric)) return list;
    for (var i = 0; i < selectors.length; i++) {
      var selector = selectors[i];
      if (seen[selector] || excepted[selector]) continue;
      seen[selector] = true;
      if (isGeneric) generic[selector] = true;
      kept.push(selector);
      list.push(selector);
    }
    return list;
  }

  function rebuild() {
    kept = disabled ? [] : kept.filter(function (selector) {
      return !excepted[selector] && !(noGeneric && generic[selector]);
    });
    if (!sheet) return;
    try { sheet.replaceSync(''); } catch (e) { return; }
    insert(kept);
  }

  function applyPage(payload) {
    if (payload.off) {
      // A list exempts this page entirely (`$document`): nothing hidden, nothing surveyed.
      disabled = true;
      rebuild();
      return;
    }
    var before = kept.length;
    noGeneric = payload.n === 1;
    var x = payload.x || [];
    for (var i = 0; i < x.length; i++) excepted[x[i]] = true;
    if (before && (noGeneric || x.length)) rebuild();
    if (payload.p && payload.p.length) procedural.add(payload.p);
    var list = fresh(payload.s || [], false).concat(fresh(payload.g || [], true));
    var applied = insert(list);
    if (DEBUG) console.log('[wren] cosmetic: ' + applied + ' of ' + list.length + ' page selectors applied');
  }

  function applySurvey(payload) {
    var list = fresh(payload.m || [], true);
    var applied = insert(list);
    if (DEBUG && list.length) console.log('[wren] cosmetic: ' + applied + ' of ' + list.length + ' surveyed selectors applied');
  }

  // Procedural rules: uBlock's `:has-text()`, `:upward()`, `:style()` and kin, which no style
  // sheet can express. Each rule is parsed once into a CSS prefix and a chain of steps, then
  // run against the DOM after parsing and again as it changes. A rule using anything not
  // understood here matches nothing: a half-applied chain would hide more than it meant.
  var procedural = (function () {
    var OPS = /^(has-text|-abp-contains|contains|min-text-length|matches-css|matches-css-before|matches-css-after|matches-attr|matches-path|matches-media|upward|xpath|has|-abp-has|if|not|if-not|watch-attr|style|remove|remove-attr|remove-class)$/;
    var ACTIONS = { 'style': 1, 'remove': 1, 'remove-attr': 1, 'remove-class': 1 };
    var token = 'a' + Math.random().toString(36).slice(2, 10);
    var HIDE = 'data-' + token;
    var procSheet = null;
    var rules = [];
    var scheduled = 0;
    var running = false;
    var added = false;

    function needleTest(needle) {
      if (needle.length > 2 && needle.charAt(0) === '/' && needle.lastIndexOf('/') > 0) {
        var last = needle.lastIndexOf('/');
        try {
          var re = new RegExp(needle.slice(1, last), needle.slice(last + 1));
          return function (text) { return re.test(text); };
        } catch (e) { return null; }
      }
      return function (text) { return text.indexOf(needle) >= 0; };
    }

    // The index of the parenthesis closing the one at `open`, past quotes and escapes.
    function closing(text, open) {
      var depth = 0;
      var quote = '';
      for (var i = open; i < text.length; i++) {
        var c = text.charAt(i);
        if (c === '\\') { i++; continue; }
        if (quote) { if (c === quote) quote = ''; continue; }
        if (c === '"' || c === "'") { quote = c; continue; }
        if (c === '(') depth++;
        else if (c === ')' && --depth === 0) return i;
      }
      return -1;
    }

    // The next `:operator(` at the top level of `text` from `from`, or null.
    function nextOp(text, from) {
      var depth = 0;
      var square = 0;
      var quote = '';
      for (var i = from; i < text.length; i++) {
        var c = text.charAt(i);
        if (c === '\\') { i++; continue; }
        if (quote) { if (c === quote) quote = ''; continue; }
        if (c === '"' || c === "'") { quote = c; continue; }
        if (c === '[') square++;
        else if (c === ']') square--;
        else if (c === '(') depth++;
        else if (c === ')') depth--;
        else if (c === ':' && depth === 0 && square === 0) {
          var m = /^:([-a-z]+)\(/.exec(text.slice(i));
          if (!m || !OPS.test(m[1])) continue;
          // A native :has()/:not() stays in the CSS unless something procedural is inside it.
          var end = closing(text, i + m[0].length - 1);
          if (end < 0) return null;
          var arg = text.slice(i + m[0].length, end);
          if ((m[1] === 'has' || m[1] === 'not') && !hasProcedural(arg)) continue;
          return { at: i, end: end + 1, name: m[1], arg: arg };
        }
      }
      return null;
    }

    function hasProcedural(text) {
      return /:(has-text|-abp-contains|contains|min-text-length|matches-css|matches-attr|matches-path|matches-media|upward|xpath|-abp-has|if|if-not|style|remove)/.test(text);
    }

    function parse(text) {
      text = text.trim();
      var first = nextOp(text, 0);
      var css = (first ? text.slice(0, first.at) : text).trim();
      var steps = [];
      var action = null;
      var op = first;
      var cursor = first ? first.at : text.length;
      while (op) {
        var between = text.slice(cursor, op.at).trim();
        if (between) steps.push(['spath', between]);
        if (ACTIONS[op.name]) {
          if (text.slice(op.end).trim()) return null; // an action ends the rule
          action = [op.name, op.arg];
        } else {
          var step = compile(op.name, op.arg);
          if (!step) return null;
          steps.push(step);
        }
        cursor = op.end;
        op = nextOp(text, cursor);
      }
      var rest = text.slice(cursor).trim();
      if (rest) steps.push(['spath', rest]);
      if (action && action[0] === 'style' && !safeStyle(action[1])) return null;
      return { css: css, steps: steps, action: action };
    }

    function safeStyle(declarations) {
      return !/url\(|image-set|expression|\\|[{}@<>]|\/\*/i.test(declarations);
    }

    function compile(name, arg) {
      switch (name) {
        case 'has-text': case '-abp-contains': case 'contains': {
          var test = needleTest(arg);
          return test && ['filter', function (el) { return test(el.textContent || ''); }];
        }
        case 'min-text-length': {
          var min = parseInt(arg, 10);
          return isNaN(min) ? null : ['filter', function (el) { return (el.textContent || '').length >= min; }];
        }
        case 'matches-css': case 'matches-css-before': case 'matches-css-after': {
          var colon = arg.indexOf(':');
          if (colon < 1) return null;
          var prop = arg.slice(0, colon).trim();
          var value = arg.slice(colon + 1).trim();
          var check = value.charAt(0) === '/' ? needleTest(value) : function (v) { return v === value; };
          var pseudo = name === 'matches-css-before' ? '::before' : name === 'matches-css-after' ? '::after' : null;
          return check && ['filter', function (el) {
            return check(String(getComputedStyle(el, pseudo).getPropertyValue(prop)));
          }];
        }
        case 'matches-attr': {
          var eq = arg.indexOf('=');
          var unquote = function (v) { v = v.trim(); return /^(["']).*\1$/.test(v) ? v.slice(1, -1) : v; };
          var nameTest = needleTest(unquote(eq < 0 ? arg : arg.slice(0, eq)));
          var valueTest = eq < 0 ? function () { return true; } : needleTest(unquote(arg.slice(eq + 1)));
          return nameTest && valueTest && ['filter', function (el) {
            for (var i = 0; i < el.attributes.length; i++) {
              var a = el.attributes[i];
              if (nameTest(a.name) && valueTest(a.value)) return true;
            }
            return false;
          }];
        }
        case 'matches-path': {
          var pathTest = needleTest(arg);
          return pathTest && ['gate', function () { return pathTest(location.pathname + location.search); }];
        }
        case 'matches-media':
          return ['gate', function () { try { return matchMedia(arg).matches; } catch (e) { return false; } }];
        case 'upward': {
          var n = /^\d+$/.test(arg.trim()) ? parseInt(arg, 10) : 0;
          if (n) return ['map', function (el) { var up = el; for (var i = 0; up && i < n; i++) up = up.parentElement; return up; }];
          return ['map', function (el) { return el.parentElement && el.parentElement.closest(arg); }];
        }
        case 'xpath':
          return ['xpath', arg];
        case 'has': case '-abp-has': case 'if': {
          var inner = parse(arg);
          return inner && !inner.action && ['filter', function (el) { return within(el, inner).length > 0; }];
        }
        case 'not': case 'if-not': {
          var self = parse(arg);
          return self && !self.action && ['filter', function (el) { return !matchesSelf(el, self); }];
        }
        case 'watch-attr':
          return ['pass'];
      }
      return null;
    }

    function scoped(css) {
      return /^[>+~]/.test(css) ? ':scope ' + css : css;
    }

    function within(root, rule) {
      var start;
      try { start = rule.css ? [].slice.call(root.querySelectorAll(scoped(rule.css))) : [root]; } catch (e) { return []; }
      return run(start, rule.steps);
    }

    function matchesSelf(el, rule) {
      try { if (rule.css && !el.matches(rule.css)) return false; } catch (e) { return false; }
      return run([el], rule.steps).length > 0;
    }

    function run(nodes, steps) {
      for (var s = 0; s < steps.length && nodes.length; s++) {
        var step = steps[s];
        var out = [];
        if (step[0] === 'filter') {
          for (var i = 0; i < nodes.length; i++) if (step[1](nodes[i])) out.push(nodes[i]);
        } else if (step[0] === 'gate') {
          out = step[1]() ? nodes : [];
        } else if (step[0] === 'map') {
          for (var j = 0; j < nodes.length; j++) {
            var mapped = step[1](nodes[j]);
            if (mapped && out.indexOf(mapped) < 0) out.push(mapped);
          }
        } else if (step[0] === 'spath') {
          for (var k = 0; k < nodes.length; k++) {
            try { out = out.concat([].slice.call(nodes[k].querySelectorAll(':scope ' + step[1]))); } catch (e) { return []; }
          }
        } else if (step[0] === 'xpath') {
          for (var x = 0; x < nodes.length; x++) {
            try {
              var found = document.evaluate(step[1], nodes[x], null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
              for (var y = 0; y < found.snapshotLength; y++) {
                var node = found.snapshotItem(y);
                if (node.nodeType === 1 && out.indexOf(node) < 0) out.push(node);
              }
            } catch (e) { return []; }
          }
        } else if (step[0] !== 'pass') {
          return [];
        }
        nodes = out;
      }
      return nodes;
    }

    function sheetFor() {
      if (procSheet) return procSheet;
      try {
        procSheet = new CSSStyleSheet();
        procSheet.insertRule('[' + HIDE + ']{display:none !important;}', 0);
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([procSheet]);
      } catch (e) { procSheet = null; }
      return procSheet;
    }

    function apply(rule, index) {
      var action = rule.action;
      // A plain selector with only a style needs no script: the sheet keeps it applied.
      if (action && action[0] === 'style' && !rule.steps.length) {
        if (!rule.styled) {
          rule.styled = true;
          try { sheetFor().insertRule(rule.css + '{' + action[1] + '}', procSheet.cssRules.length); } catch (e) {}
        }
        return;
      }
      var nodes = rule.css ? within(document, rule) : run([document.documentElement], rule.steps);
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        if (!action) {
          if (sheetFor()) el.setAttribute(HIDE, '');
        } else if (action[0] === 'remove') {
          el.remove();
        } else if (action[0] === 'remove-attr') {
          action[1].split('|').forEach(function (n) { n = n.trim(); if (n) el.removeAttribute(n); });
        } else if (action[0] === 'remove-class') {
          action[1].split('|').forEach(function (n) { n = n.trim(); if (n) el.classList.remove(n); });
        } else if (action[0] === 'style') {
          var attr = HIDE + '-' + index;
          if (!rule.styled) {
            rule.styled = true;
            try { sheetFor().insertRule('[' + attr + ']{' + action[1] + '}', procSheet.cssRules.length); } catch (e) {}
          }
          el.setAttribute(attr, '');
        }
      }
    }

    function runAll() {
      scheduled = 0;
      if (running || disabled) return;
      running = true;
      for (var i = 0; i < rules.length; i++) {
        try { apply(rules[i], i); } catch (e) {}
      }
      running = false;
    }

    function schedule() {
      if (!scheduled && rules.length) scheduled = setTimeout(runAll, 100);
    }

    return {
      add: function (list) {
        // The page's answer can come more than once (it is asked again until it replies).
        if (added) return;
        added = true;
        for (var i = 0; i < list.length; i++) {
          var rule = null;
          try { rule = parse(list[i]); } catch (e) {}
          if (rule && (rule.css || rule.steps.length)) rules.push(rule);
        }
        if (!rules.length) return;
        if (document.readyState === 'loading') {
          document.addEventListener('DOMContentLoaded', runAll, { once: true });
        } else {
          runAll();
        }
        try {
          new MutationObserver(function () { if (!running) schedule(); })
            .observe(document, { childList: true, subtree: true, characterData: true });
        } catch (e) {}
      }
    };
  })();

  insert(fresh(FALLBACK, true));

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
    if (!ready || disabled || noGeneric || (!pendingIds.length && !pendingClasses.length)) return;
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
      if (payload.g || payload.s || payload.off) {
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
