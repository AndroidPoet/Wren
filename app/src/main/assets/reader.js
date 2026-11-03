/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Reader mode: finds the article on the page and throws the rest away.
 *
 * A trimmed-down version of the scoring Readability uses. Paragraphs vote for their parent, a
 * parent with many long paragraphs wins, and containers whose class or id looks like furniture
 * are penalised. It is not as good as the real thing on hostile markup, but it is a few hundred
 * lines rather than a few thousand and it handles ordinary articles well.
 *
 * Returns true when it replaced the page, false when it could not find an article - the caller
 * says so rather than leaving a blank screen.
 */
(function () {
  'use strict';

  var FURNITURE = new RegExp(
    'comment|share|share-|sidebar|footer|header|nav|menu|banner|promo|related|recommend|' +
    'popup|modal|newsletter|subscribe|advert|sponsor|social|breadcrumb|pagination|widget',
    'i'
  );
  var CONTENT_HINT = /article|body|content|entry|main|post|story|text/i;
  // Structural furniture and anything interactive. Deliberately does NOT touch anchors: they
  // carry caption and body text, and removing them leaves sentences with holes in them.
  var STRIP = 'script,style,noscript,iframe,form,input,button,select,textarea,svg,' +
    'nav,aside,footer,header,link,meta,object,embed';
  var MIN_PARAGRAPH = 25;
  var MIN_ARTICLE_TEXT = 400;

  function furniturePenalty(node) {
    var signature = (node.className || '') + ' ' + (node.id || '');
    if (typeof signature !== 'string') return 0;
    var score = 0;
    if (FURNITURE.test(signature)) score -= 25;
    if (CONTENT_HINT.test(signature)) score += 25;
    return score;
  }

  function scoreParagraphs() {
    var scores = new Map();
    var paragraphs = document.querySelectorAll('p, pre, blockquote, li > p');

    for (var i = 0; i < paragraphs.length; i++) {
      var paragraph = paragraphs[i];
      var text = (paragraph.innerText || paragraph.textContent || '').trim();
      if (text.length < MIN_PARAGRAPH) continue;

      // Longer paragraphs, and ones with more clauses, are more likely to be prose.
      var value = 1 + text.split(',').length + Math.min(Math.floor(text.length / 100), 3);

      var parent = paragraph.parentElement;
      var grandparent = parent && parent.parentElement;
      if (parent) scores.set(parent, (scores.get(parent) || furniturePenalty(parent)) + value);
      // A grandparent gets half a vote, which is what lets a wrapper beat its own children.
      if (grandparent) {
        scores.set(
          grandparent,
          (scores.get(grandparent) || furniturePenalty(grandparent)) + value / 2
        );
      }
    }
    return scores;
  }

  function bestCandidate(scores) {
    var best = null;
    var bestScore = 0;
    scores.forEach(function (score, node) {
      // Wall-to-wall links is a navigation block wearing an article's clothes.
      var text = (node.innerText || '').length;
      if (text < MIN_ARTICLE_TEXT) return;
      var linkText = 0;
      var links = node.querySelectorAll('a');
      for (var i = 0; i < links.length; i++) linkText += (links[i].innerText || '').length;
      var linkDensity = text === 0 ? 1 : linkText / text;
      var adjusted = score * (1 - linkDensity);
      if (adjusted > bestScore) {
        bestScore = adjusted;
        best = node;
      }
    });
    return best;
  }

  function articleTitle() {
    var meta = document.querySelector('meta[property="og:title"]');
    if (meta && meta.content) return meta.content;
    var heading = document.querySelector('h1');
    if (heading && heading.innerText.trim()) return heading.innerText.trim();
    return document.title || '';
  }

  function articleByline() {
    var candidates = [
      'meta[name="author"]',
      '[rel="author"]',
      '[itemprop="author"]',
      '.byline',
      '.author',
    ];
    for (var i = 0; i < candidates.length; i++) {
      var node = document.querySelector(candidates[i]);
      if (!node) continue;
      var value = node.content || node.innerText || '';
      value = value.trim();
      if (value && value.length < 120) return value;
    }
    return '';
  }

  function clean(node) {
    var copy = node.cloneNode(true);
    var junk = copy.querySelectorAll(STRIP);
    for (var i = 0; i < junk.length; i++) {
      if (junk[i].parentNode) junk[i].parentNode.removeChild(junk[i]);
    }
    // Inline styles and widths fight the reader stylesheet.
    var all = copy.querySelectorAll('*');
    for (var j = 0; j < all.length; j++) {
      all[j].removeAttribute('style');
      all[j].removeAttribute('width');
      all[j].removeAttribute('height');
      all[j].removeAttribute('class');
      all[j].removeAttribute('id');
    }
    return copy;
  }

  var STYLE = [
    'html,body{margin:0;padding:0;background:__BG__ !important;color:__FG__ !important;}',
    '#wren-reader{max-width:38em;margin:0 auto;padding:28px 22px 64px;',
    'font-size:1.05rem;line-height:1.7;',
    'font-family:Georgia,"Noto Serif",serif;}',
    '#wren-reader h1{font-size:1.7rem;line-height:1.25;margin:0 0 8px;',
    'font-family:system-ui,sans-serif;}',
    '#wren-reader .wren-byline{color:__MUTED__;font-size:.85rem;margin-bottom:28px;',
    'font-family:system-ui,sans-serif;}',
    '#wren-reader p{margin:0 0 1.15em;}',
    '#wren-reader a{color:__ACCENT__;}',
    '#wren-reader img{max-width:100%;height:auto;border-radius:8px;margin:1em 0;}',
    '#wren-reader pre,#wren-reader code{background:__SURFACE__;border-radius:6px;',
    'font-family:monospace;font-size:.9rem;}',
    '#wren-reader pre{padding:12px;overflow-x:auto;}',
    '#wren-reader blockquote{margin:1.2em 0;padding-left:1em;',
    'border-left:3px solid __ACCENT__;color:__MUTED__;}',
    '#wren-reader h2,#wren-reader h3{font-family:system-ui,sans-serif;margin:1.6em 0 .5em;}',
  ].join('');

  try {
    var candidate = bestCandidate(scoreParagraphs());
    if (!candidate) return false;

    var content = clean(candidate);
    var title = articleTitle();
    var byline = articleByline();

    // Build the replacement before touching the document, so a failure leaves the page intact.
    var article = document.createElement('div');
    article.id = 'wren-reader';
    if (title) {
      var heading = document.createElement('h1');
      heading.textContent = title;
      article.appendChild(heading);
    }
    if (byline) {
      var bylineNode = document.createElement('div');
      bylineNode.className = 'wren-byline';
      bylineNode.textContent = byline;
      article.appendChild(bylineNode);
    }
    article.appendChild(content);

    var style = document.createElement('style');
    style.appendChild(document.createTextNode(STYLE));

    document.body.innerHTML = '';
    document.body.appendChild(style);
    document.body.appendChild(article);
    document.documentElement.scrollTop = 0;
    return true;
  } catch (ignored) {
    return false;
  }
})();
