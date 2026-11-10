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

  // What a page waits for is the callback, not the hit: ga(function (tracker) {...}), a
  // hitCallback in send fields, gtag's event_callback and a data-layer eventCallback all gate
  // navigation or initialisation on the tracker answering. Each is called, soon, with an inert
  // tracker, and nothing is sent anywhere.
  function later(fn, argument) {
    try {
      setTimeout(function () {
        try { fn(argument); } catch (ignored) {}
      }, 0);
    } catch (ignored) {}
  }

  var tracker = { get: noop, set: noop, send: noop };

  function answer(args) {
    for (var i = 0; i < args.length; i++) {
      var arg = args[i];
      if (typeof arg === 'function') {
        later(arg, tracker);
      } else if (arg && typeof arg === 'object') {
        if (typeof arg.hitCallback === 'function') later(arg.hitCallback);
        if (typeof arg.event_callback === 'function') later(arg.event_callback);
        if (typeof arg.eventCallback === 'function') later(arg.eventCallback);
      }
    }
  }

  // ga(...) with the queue API sites poll for readiness. The snippet's own stub, when it ran
  // first, has been queueing calls into ga.q; those are answered too.
  var existing = window.ga;
  var queued = existing && existing.q;
  if (typeof existing !== 'function' || queued) {
    var ga = function () { answer(arguments); };
    ga.q = [];
    ga.l = (existing && existing.l) || Number(new Date());
    ga.create = function () { return tracker; };
    ga.getByName = function () { return null; };
    ga.getAll = function () { return []; };
    ga.remove = noop;
    window.ga = ga;
    if (queued && typeof queued.length === 'number') {
      for (var i = 0; i < queued.length; i++) {
        try { answer(queued[i]); } catch (ignored) {}
      }
    }
  }

  // gtag(...) and the array it normally flushes into; pushes still accumulate, since pages
  // read the data layer back, and their completion callbacks run.
  window.dataLayer = window.dataLayer || [];
  var layer = window.dataLayer;
  if (typeof layer.push !== 'function') {
    layer.push = noop;
  } else {
    var nativePush = layer.push;
    layer.push = function () {
      try { answer(arguments); } catch (ignored) {}
      return nativePush.apply(this, arguments);
    };
  }
  if (typeof window.gtag !== 'function') {
    window.gtag = function () { answer(arguments); };
  }

  // Universal Analytics tracker objects, for code that reaches past ga().
  if (!window.GoogleAnalyticsObject) window.GoogleAnalyticsObject = 'ga';

  // google_tag_manager keyed by container id; sites check for their own container.
  window.google_tag_manager = window.google_tag_manager || {};

  // Some tags signal readiness through a callback queue.
  if (window.google_tag_data === undefined) window.google_tag_data = {};
})();
