/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Answers cookie consent pop-ups with the most private option and gets them off the screen.
 *
 * Every rule here clicks *reject*, never accept. Dismissing a banner by accepting it would be
 * worse than leaving it alone: the pop-up would go away while the consent it was asking for was
 * silently granted.
 *
 * Two passes. Known consent platforms are matched by their own markup and clicked precisely,
 * because a wrong click on a real banner has consequences. Only if none match does a generic
 * pass look at button text, and even then it is confined to a container that identifies itself
 * as a consent dialog.
 */
(function () {
  'use strict';

  var DEADLINE_MS = 12000;
  var INTERVAL_MS = 400;
  var started = Date.now();
  var done = false;

  /**
   * Known consent platforms. `detect` proves the platform is present, `reject` is its
   * most-private control. Ordered roughly by how common they are.
   */
  var PLATFORMS = [
    {
      name: 'OneTrust',
      detect: '#onetrust-consent-sdk, #onetrust-banner-sdk',
      reject: '#onetrust-reject-all-handler, .onetrust-close-btn-handler.banner-close-button, ' +
        '.ot-pc-refuse-all-handler',
    },
    {
      name: 'Cookiebot',
      detect: '#CybotCookiebotDialog',
      reject: '#CybotCookiebotDialogBodyButtonDecline, ' +
        '#CybotCookiebotDialogBodyLevelButtonLevelOptinDeclineAll',
    },
    {
      name: 'Quantcast',
      detect: '.qc-cmp2-container, #qc-cmp2-container',
      reject: '.qc-cmp2-summary-buttons button[mode="secondary"], .qc-cmp2-footer button.css-1hy2vtq',
    },
    {
      name: 'Didomi',
      detect: '#didomi-popup, .didomi-popup-container',
      reject: '#didomi-notice-disagree-button, .didomi-continue-without-agreeing',
    },
    {
      name: 'Usercentrics',
      detect: '#usercentrics-root, [data-testid="uc-container"]',
      reject: '[data-testid="uc-deny-all-button"], #uc-btn-deny-banner',
    },
    {
      name: 'Sourcepoint',
      detect: '.sp_message_container, .message-container',
      reject: 'button.sp_choice_type_REJECT_ALL, button[title="Reject All"]',
    },
    {
      name: 'CookieYes',
      detect: '.cky-consent-container, #cookie-law-info-bar',
      reject: '.cky-btn-reject, #cookie_action_close_header_reject',
    },
    {
      name: 'Osano',
      detect: '.osano-cm-dialog',
      reject: '.osano-cm-denyAll, .osano-cm-button--type-deny',
    },
    {
      name: 'Complianz',
      detect: '#cmplz-cookiebanner-container',
      reject: '.cmplz-deny',
    },
    {
      name: 'Borlabs',
      detect: '#BorlabsCookieBox',
      reject: 'a.borlabs-cookie-refuse, ._brlbs-btn-refuse',
    },
    {
      name: 'TrustArc',
      detect: '#truste-consent-track',
      reject: '#truste-consent-required',
    },
    {
      name: 'Termly',
      detect: '#termly-code-snippet-support',
      reject: '[data-tid="banner-decline"]',
    },
  ];

  // Only text inside something that says it is a consent dialog is eligible for the fallback.
  var CONSENT_CONTAINER = new RegExp('cookie|consent|gdpr|cmp|privacy-(banner|notice)', 'i');
  var REJECT_TEXT = new RegExp(
    '^(reject all|reject|decline all|decline|refuse all|refuse|' +
    'only necessary|necessary only|only essential|essential only|' +
    'use necessary cookies only|continue without accepting|deny all|deny)$',
    'i'
  );

  function visible(node) {
    if (!node) return false;
    var rect = node.getBoundingClientRect();
    if (rect.width === 0 && rect.height === 0) return false;
    var style = window.getComputedStyle(node);
    return style.visibility !== 'hidden' && style.display !== 'none';
  }

  function clickFirst(selector) {
    var nodes = document.querySelectorAll(selector);
    for (var i = 0; i < nodes.length; i++) {
      if (visible(nodes[i])) {
        nodes[i].click();
        return true;
      }
    }
    return false;
  }

  function tryKnownPlatforms() {
    for (var i = 0; i < PLATFORMS.length; i++) {
      var platform = PLATFORMS[i];
      try {
        if (!document.querySelector(platform.detect)) continue;
        if (clickFirst(platform.reject)) return platform.name;
      } catch (ignored) {
        // A malformed selector on one platform must not stop the rest.
      }
    }
    return null;
  }

  function insideConsentContainer(node) {
    var parent = node;
    for (var depth = 0; parent && depth < 8; depth++) {
      var signature = (parent.id || '') + ' ' + (typeof parent.className === 'string'
        ? parent.className
        : '');
      if (CONSENT_CONTAINER.test(signature)) return true;
      parent = parent.parentElement;
    }
    return false;
  }

  function tryGenericReject() {
    var controls = document.querySelectorAll('button, a[role="button"], [role="button"]');
    for (var i = 0; i < controls.length; i++) {
      var control = controls[i];
      var label = (control.innerText || control.textContent || '').trim();
      if (!label || label.length > 40) continue;
      if (!REJECT_TEXT.test(label)) continue;
      if (!visible(control) || !insideConsentContainer(control)) continue;
      control.click();
      return label;
    }
    return null;
  }

  function attempt() {
    if (done) return;
    if (Date.now() - started > DEADLINE_MS) {
      done = true;
      return;
    }
    try {
      if (tryKnownPlatforms() || tryGenericReject()) done = true;
    } catch (ignored) {
      // Never let a consent script take the page down with it.
    }
  }

  // Banners are usually injected after load, so poll rather than run once.
  var timer = window.setInterval(function () {
    attempt();
    if (done) window.clearInterval(timer);
  }, INTERVAL_MS);

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', attempt, true);
  } else {
    attempt();
  }
})();
