/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * True where Wren's page scripts must stay out, as an expression the scripts are wrapped in.
 *
 * A bot check's frame (Cloudflare, hCaptcha, reCAPTCHA, Arkose, DataDome, GeeTest, AWS WAF)
 * measures the page's APIs to decide whether a person is there, and reads any change to them
 * as automation: with Wren's shims in it, the check fails and the site behind it never opens.
 * The vendor sees the visit through the check either way, so it runs on the engine as it is.
 *
 * Also true on a page whose top site the user turned protection off for (the list below), and in
 * every frame of that page, since a site's own frames are where it usually breaks. Not used
 * under Tor, where these scripts are part of what keeps one Tor identity from another.
 */
(function () {
  try {
    var own = location.hostname;
    if (!own && typeof origin === 'string' && origin !== 'null') own = new URL(origin).hostname;
    if (/(^|\.)(challenges\.cloudflare\.com|hcaptcha\.com|arkoselabs\.com|funcaptcha\.com|captcha-delivery\.com|geetest\.com|awswaf\.com|recaptcha\.net)$/.test(own)) {
      return true;
    }
    if (own === 'www.google.com' && location.pathname.indexOf('/recaptcha/') === 0) return true;
    var ancestors = location.ancestorOrigins;
    var top = ancestors && ancestors.length ? new URL(ancestors[ancestors.length - 1]).hostname : own;
    return __OFF__.indexOf(top) !== -1;
  } catch (ignored) {
    return false;
  }
})()
