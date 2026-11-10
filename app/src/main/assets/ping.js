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

  // Everything the strip relies on is captured now, before any page script runs: a page that
  // replaced composedPath, hasAttribute or removeAttribute later would otherwise keep its ping.
  var apply = Reflect.apply;
  var defineProperty = Object.defineProperty;
  var getOwn = Object.getOwnPropertyDescriptor;
  var weakGet = WeakMap.prototype.get;
  var weakSet = WeakMap.prototype.set;
  var nativeToString = Function.prototype.toString;
  var SOURCES = Symbol.for('wren.sources');
  var EventProto = window.Event && window.Event.prototype;
  var composedPath = EventProto && EventProto.composedPath;
  var targetGetter = getterOf(EventProto, 'target');
  var NodeProto = window.Node && window.Node.prototype;
  var nodeTypeGetter = getterOf(NodeProto, 'nodeType');
  var parentGetter = getterOf(NodeProto, 'parentNode');
  var ElementProto = window.Element && window.Element.prototype;
  var hasAttribute = ElementProto ? ElementProto.hasAttribute : null;
  var removeAttribute = ElementProto ? ElementProto.removeAttribute : null;
  var addListener = window.EventTarget && window.EventTarget.prototype.addEventListener;

  function getterOf(proto, name) {
    try {
      var descriptor = proto && getOwn(proto, name);
      return descriptor && descriptor.get ? descriptor.get : null;
    } catch (ignored) { return null; }
  }

  function read(getter, object, name) {
    return getter ? apply(getter, object, []) : object[name];
  }

  function call(native, object, name, args) {
    return apply(native || object[name], object, args);
  }

  function listen(target, type, capture) {
    try { call(addListener, target, 'addEventListener', [type, strip, capture]); } catch (ignored) {}
  }

  function strip(event) {
    var path;
    try { path = call(composedPath, event, 'composedPath', []); } catch (ignored) { path = null; }
    if (!path || !path.length) {
      path = [];
      try {
        var node = read(targetGetter, event, 'target');
        while (node) { path[path.length] = node; node = read(parentGetter, node, 'parentNode'); }
      } catch (ignored) {}
    }
    for (var i = 0; i < path.length; i++) {
      var element = path[i];
      try {
        if (element && read(nodeTypeGetter, element, 'nodeType') === 1 &&
          call(hasAttribute, element, 'hasAttribute', ['ping'])) {
          call(removeAttribute, element, 'removeAttribute', ['ping']);
        }
      } catch (ignored) {}
    }
  }

  // Seen from the window, a click inside a closed shadow root is retargeted to its host: the
  // link that carries the ping is not on the path. Every root a script attaches therefore gets
  // the same pair of listeners, which see the inside. A root declared in the markup
  // (<template shadowrootmode>) is built by the parser, not by attachShadow, and is not reached.
  function sourcesOf() {
    var map = window[SOURCES];
    if (map) return map;
    map = new WeakMap();
    try {
      defineProperty(window, SOURCES, { value: map });
      var FunctionPrototype = window.Function.prototype;
      var toString = FunctionPrototype.toString;
      var masked = function toString() {
        var text = apply(weakGet, map, [this]);
        return text !== undefined ? text : apply(nativeToString, this, []);
      };
      apply(weakSet, map, [masked, apply(nativeToString, toString, [])]);
      defineProperty(FunctionPrototype, 'toString', { value: masked, writable: true, configurable: true });
    } catch (ignored) {}
    return map;
  }

  var nativeAttachShadow = ElementProto && ElementProto.attachShadow;
  if (typeof nativeAttachShadow === 'function') {
    try {
      var attachShadow = {
        attachShadow: function (init) {
          var root = apply(nativeAttachShadow, this, arguments);
          for (var t = 0; t < TYPES.length; t++) {
            listen(root, TYPES[t], true);
            listen(root, TYPES[t], false);
          }
          return root;
        },
      }.attachShadow;
      apply(weakSet, sourcesOf(), [attachShadow, apply(nativeToString, nativeAttachShadow, [])]);
      var descriptor = getOwn(ElementProto, 'attachShadow');
      descriptor.value = attachShadow;
      defineProperty(ElementProto, 'attachShadow', descriptor);
    } catch (ignored) {}
  }

  // A middle-click or a modifier-click opens the link too, as auxclick; both are seen first here,
  // in the capture phase on the window, before any handler of the page, and once more as the
  // event bubbles back to the window, after the page's handlers: one that put the attribute
  // back on the way is undone before the engine activates the link. A handler that stops the
  // event from bubbling keeps its ping; that case is the engine's to close.
  var TYPES = ['click', 'auxclick'];
  for (var k = 0; k < TYPES.length; k++) {
    listen(window, TYPES[k], true);
    listen(window, TYPES[k], false);
  }
})();
