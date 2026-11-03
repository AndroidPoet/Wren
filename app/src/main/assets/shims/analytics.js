/*
 * Copyright 2026 AndroidPoet
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Stands in for Google Analytics, gtag and Tag Manager.
 *
 * Blocking these outright is what breaks pages: sites call ga(), gtag() or push onto dataLayer
 * during initialisation, often inside the same try block as something they actually need, and a
 * ReferenceError there takes the rest of the page down with it. This defines the surface those
 * call sites touch and drops everything on the floor. Nothing is recorded and nothing is sent.
 */
(function () {
  'use strict';

  function noop() {}

  // ga(...) with the queue API sites poll for readiness.
  if (typeof window.ga !== 'function') {
    var ga = function () {};
    ga.q = [];
    ga.l = Number(new Date());
    ga.create = function () {
      return { get: noop, set: noop, send: noop };
    };
    ga.getByName = function () {
      return null;
    };
    ga.getAll = function () {
      return [];
    };
    ga.remove = noop;
    window.ga = ga;
  }

  // gtag(...) and the array it normally flushes into.
  window.dataLayer = window.dataLayer || [];
  if (typeof window.dataLayer.push !== 'function') window.dataLayer.push = noop;
  if (typeof window.gtag !== 'function') {
    window.gtag = function () {};
  }

  // Universal Analytics tracker objects, for code that reaches past ga().
  if (!window.GoogleAnalyticsObject) window.GoogleAnalyticsObject = 'ga';

  // google_tag_manager keyed by container id; sites check for their own container.
  window.google_tag_manager = window.google_tag_manager || {};

  // Some tags signal readiness through a callback queue.
  if (window.google_tag_data === undefined) window.google_tag_data = {};
})();
