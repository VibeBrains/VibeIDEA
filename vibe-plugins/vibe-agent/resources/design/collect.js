/*
 * Snapshot collector: runs INSIDE the measured page and reports what the browser actually computed.
 *
 * Two rules govern everything here. It must not change the page — no focusing, no scrolling, no
 * class juggling: a measurement that alters what it measures is worthless. And it must say when it
 * could not look: a stylesheet blocked by CORS makes "no :focus rule" mean "did not see", which the
 * rules treat differently from "there is none".
 */
(function () {
  var MAX_ELEMENTS = 1200;
  var MAX_TEXT = 200;
  var LINE_SAMPLE = 120; // measuring line breaks costs a Range per element — budget it

  function rgb(value) {
    var m = /rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/.exec(value || '');
    if (!m) return { c: [0, 0, 0], a: 0 };
    return { c: [+m[1], +m[2], +m[3]], a: m[4] === undefined ? 1 : parseFloat(m[4]) };
  }

  /** Effective background: walk up through transparent parents, the way a reader's eye does. */
  function effectiveBackground(el) {
    var node = el;
    while (node && node !== document.documentElement) {
      var parsed = rgb(getComputedStyle(node).backgroundColor);
      if (parsed.a > 0.95) return parsed.c;
      node = node.parentElement;
    }
    return [255, 255, 255];
  }

  function selectorOf(el) {
    if (el.id) return '#' + el.id;
    var parts = [];
    var node = el;
    var depth = 0;
    while (node && node.nodeType === 1 && depth < 5) {
      var part = node.tagName.toLowerCase();
      if (node.classList.length) part += '.' + Array.prototype.slice.call(node.classList, 0, 2).join('.');
      parts.unshift(part);
      node = node.parentElement;
      depth++;
    }
    return parts.join(' > ');
  }

  /** Selector rules matching this element, read from the document's own stylesheets. */
  var stylesUnreadable = false;
  var focusSelectors = [];
  var hoverSelectors = [];
  var activeSelectors = [];
  // Whether ANY stylesheet answers prefers-reduced-motion. Read once for the page: motion the
  // system asked to stop is not a per-element property.
  var reducedMotionRule = false;
  (function readStyleRules() {
    for (var i = 0; i < document.styleSheets.length; i++) {
      var sheet = document.styleSheets[i];
      var rules;
      try { rules = sheet.cssRules; } catch (e) { stylesUnreadable = true; continue; }
      if (!rules) { stylesUnreadable = true; continue; }
      for (var j = 0; j < rules.length; j++) {
        var text = rules[j].selectorText;
        if (!text) continue;
        if (text.indexOf(':focus') >= 0) focusSelectors.push(text);
        if (text.indexOf(':hover') >= 0) hoverSelectors.push(text);
        if (text.indexOf(':active') >= 0) activeSelectors.push(text);
      }
      for (var k = 0; k < rules.length; k++) {
        var media = rules[k].media && rules[k].media.mediaText;
        if (media && media.indexOf('prefers-reduced-motion') >= 0) reducedMotionRule = true;
      }
    }
  })();

  /** Escapes an id for use inside a selector — CSS.escape is absent in older engines. */
  function cssEscape(value) {
    if (window.CSS && CSS.escape) return CSS.escape(value);
    return String(value).replace(/[^a-zA-Z0-9_-]/g, '\\$&');
  }

  function matchesAny(el, selectors, pseudo) {
    for (var i = 0; i < selectors.length; i++) {
      var bare = selectors[i].split(',').map(function (s) { return s.replace(pseudo, '').trim(); });
      for (var j = 0; j < bare.length; j++) {
        if (!bare[j]) continue;
        try { if (el.matches(bare[j])) return true; } catch (e) { /* invalid selector: ignore */ }
      }
    }
    return false;
  }

  function accessibleName(el) {
    var label = el.getAttribute('aria-label');
    if (label && label.trim()) return label.trim();
    var labelledBy = el.getAttribute('aria-labelledby');
    if (labelledBy) {
      var names = labelledBy.split(/\s+/).map(function (id) {
        var target = document.getElementById(id);
        return target ? (target.textContent || '').trim() : '';
      }).filter(Boolean);
      if (names.length) return names.join(' ');
    }
    if (el.id) {
      var explicit = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
      if (explicit) return (explicit.textContent || '').trim();
    }
    var wrapping = el.closest && el.closest('label');
    if (wrapping) return (wrapping.textContent || '').trim();
    var text = (el.textContent || '').trim();
    if (text) return text;
    var alt = el.getAttribute('alt');
    if (alt) return alt.trim();
    var title = el.getAttribute('title');
    return title ? title.trim() : '';
  }

  function describedBy(el) {
    var ids = el.getAttribute('aria-describedby');
    if (!ids) return '';
    return ids.split(/\s+/).map(function (id) {
      var target = document.getElementById(id);
      return target ? (target.textContent || '').trim() : '';
    }).filter(Boolean).join(' ');
  }

  /**
   * How the text really broke into lines. Cannot be derived from the source — it depends on the
   * font, the box and the browser's hyphenation, so it is measured with a Range and only for a
   * budgeted sample: every word costs a rect.
   */
  function lineMetrics(el, budgetLeft) {
    var empty = { lines: 0, shortEnds: 0, lastWords: 0 };
    if (budgetLeft <= 0) return empty;
    if (!el.firstChild || el.firstChild.nodeType !== 3) return empty;
    var text = (el.textContent || '').trim();
    if (text.length < 20) return empty;
    var range = document.createRange();
    range.selectNodeContents(el);
    var rects = range.getClientRects();
    if (!rects || rects.length < 2) return empty;
    var tops = [];
    for (var i = 0; i < rects.length; i++) {
      var top = Math.round(rects[i].top);
      if (tops.indexOf(top) < 0) tops.push(top);
    }
    var words = text.split(/\s+/);
    // Which words end a visual line: walk word by word, comparing the top of each word's rect.
    var shortEnds = 0;
    var lastLineWords = 0;
    var previousTop = null;
    var offset = 0;
    var node = el.firstChild;
    var perLine = [];
    for (var w = 0; w < words.length; w++) {
      var start = text.indexOf(words[w], offset);
      if (start < 0) break;
      offset = start + words[w].length;
      var wordRange = document.createRange();
      try {
        wordRange.setStart(node, start);
        wordRange.setEnd(node, offset);
      } catch (e) { break; }
      var rect = wordRange.getBoundingClientRect();
      var top = Math.round(rect.top);
      if (previousTop !== null && top !== previousTop) {
        perLine.push(words[w - 1]);
      }
      previousTop = top;
      lastLineWords = (previousTop === top) ? lastLineWords : 0;
    }
    for (var k = 0; k < perLine.length; k++) {
      if (perLine[k] && perLine[k].replace(/[^\wа-яА-ЯёЁ]/g, '').length <= 2) shortEnds++;
    }
    // Words on the last visual line.
    var lastTop = Math.round(rects[rects.length - 1].top);
    var count = 0;
    offset = 0;
    for (var q = 0; q < words.length; q++) {
      var s = text.indexOf(words[q], offset);
      if (s < 0) break;
      offset = s + words[q].length;
      var r2 = document.createRange();
      try { r2.setStart(node, s); r2.setEnd(node, offset); } catch (e) { break; }
      if (Math.round(r2.getBoundingClientRect().top) === lastTop) count++;
    }
    return { lines: tops.length, shortEnds: shortEnds, lastWords: count };
  }

  var all = document.querySelectorAll('body *');
  var indexOf = new Map();
  var elements = [];
  var lineBudget = LINE_SAMPLE;

  for (var i = 0; i < all.length && elements.length < MAX_ELEMENTS; i++) {
    var el = all[i];
    var style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden') continue;
    var rect = el.getBoundingClientRect();
    var own = rgb(style.backgroundColor);
    var tag = el.tagName.toLowerCase();
    var isField = tag === 'input' || tag === 'select' || tag === 'textarea';
    var interactive = tag === 'button' || tag === 'a' || el.getAttribute('role') === 'button' ||
                      isField || typeof el.onclick === 'function';
    var directText = '';
    for (var c = 0; c < el.childNodes.length; c++) {
      if (el.childNodes[c].nodeType === 3) directText += el.childNodes[c].nodeValue;
    }
    directText = directText.trim().replace(/\s+/g, ' ').slice(0, MAX_TEXT);

    var metrics = { lines: 0, shortEnds: 0, lastWords: 0 };
    if (directText) {
      metrics = lineMetrics(el, lineBudget);
      if (metrics.lines) lineBudget--;
    }

    indexOf.set(el, elements.length);
    var parentIndex = el.parentElement && indexOf.has(el.parentElement) ? indexOf.get(el.parentElement) : -1;

    elements.push({
      selector: selectorOf(el),
      parentId: parentIndex,
      tag: tag,
      text: directText,
      classes: Array.prototype.slice.call(el.classList, 0, 6),
      childTags: Array.prototype.map.call(el.children, function (child) { return child.tagName.toLowerCase(); }).slice(0, 6),
      fontSizePx: parseFloat(style.fontSize) || 0,
      lineHeightPx: parseFloat(style.lineHeight) || 0,
      letterSpacingPx: parseFloat(style.letterSpacing) || 0,
      fontFamily: style.fontFamily,
      fontWeight: parseInt(style.fontWeight, 10) || 400,
      fontStyle: style.fontStyle,
      textTransform: style.textTransform,
      color: rgb(style.color).c,
      backgroundColor: effectiveBackground(el),
      ownBackgroundAlpha: own.a,
      backgroundImage: style.backgroundImage,
      backgroundClip: style.webkitBackgroundClip || style.backgroundClip,
      boxShadow: style.boxShadow,
      backdropFilter: style.backdropFilter || style.webkitBackdropFilter || '',
      borderRadiusPx: parseFloat(style.borderTopLeftRadius) || 0,
      animationName: style.animationName,
      animationTimingFunction: style.animationTimingFunction,
      animationDurationMs: (parseFloat(style.animationDuration) || 0) * 1000,
      transitionProperty: style.transitionProperty,
      position: style.position,
      zIndex: parseInt(style.zIndex, 10) || 0,
      overflowX: style.overflowX,
      overflowY: style.overflowY,
      widthPx: rect.width,
      heightPx: rect.height,
      leftPx: rect.left + window.scrollX,
      topPx: rect.top + window.scrollY,
      scrollWidthPx: el.scrollWidth,
      clientWidthPx: el.clientWidth,
      imgSrc: tag === 'img' ? (el.currentSrc || el.src || '') : '',
      imgNaturalWidthPx: tag === 'img' ? (el.naturalWidth || 0) : 0,
      svgShapeCount: el.querySelectorAll ? el.querySelectorAll('svg path, svg circle, svg rect, svg line').length : 0,
      textLineCount: metrics.lines,
      linesEndingWithShortWord: metrics.shortEnds,
      lastLineWordCount: metrics.lastWords,
      interactive: !!interactive,
      outlineStyle: style.outlineStyle,
      outlineWidthPx: parseFloat(style.outlineWidth) || 0,
      hasFocusRule: matchesAny(el, focusSelectors, /:focus(-visible)?/g),
      hasHoverRule: matchesAny(el, hoverSelectors, /:hover/g),
      disabled: !!el.disabled || el.getAttribute('aria-disabled') === 'true',
      styleRulesUnreadable: stylesUnreadable,
      accessibleName: accessibleName(el),
      isFormField: isField,
      inputType: (el.getAttribute('type') || '').toLowerCase(),
      hasPlaceholder: !!el.getAttribute('placeholder'),
      hasAltAttribute: el.hasAttribute && el.hasAttribute('alt'),
      ariaInvalid: el.getAttribute('aria-invalid') === 'true',
      describedByText: describedBy(el),
      isRequiredField: !!el.required || el.getAttribute('aria-required') === 'true',
      autocompleteAttr: (el.getAttribute('autocomplete') || '').toLowerCase(),
      inputMode: (el.getAttribute('inputmode') || '').toLowerCase(),
      fieldName: (el.getAttribute('name') || '').toLowerCase(),
      readOnly: !!el.readOnly,
      // A real <label>, not merely an accessible name: aria-label is invisible to a sighted person.
      hasLabelElement: !!(el.id && document.querySelector('label[for="' + cssEscape(el.id) + '"]')) || !!el.closest('label'),
      placeholderText: (el.getAttribute('placeholder') || '').trim().slice(0, 120),
      insideForm: !!el.closest('form'),
      cursorStyle: style.cursor,
      animationIterationCount: style.animationIterationCount,
      transitionDurationMs: (parseFloat(style.transitionDuration) || 0) * 1000,
      transitionTimingFunction: style.transitionTimingFunction,
      hasActiveRule: matchesAny(el, activeSelectors, /:active/g),
      textDecorationLine: style.textDecorationLine || 'none',
      borderColor: rgb(style.borderTopColor).c,
      borderWidthPx: parseFloat(style.borderTopWidth) || 0,
      opacity: parseFloat(style.opacity),
      outlineColor: rgb(style.outlineColor).c
    });
  }

  var headings = Array.prototype.map.call(document.querySelectorAll('h1,h2,h3,h4,h5,h6'), function (h) {
    return { tag: h.tagName.toLowerCase(), text: (h.textContent || '').trim().slice(0, 120), fontSizePx: parseFloat(getComputedStyle(h).fontSize) || 0 };
  });

  // Findability is read from the LIVE page rather than from the source: what shipped is what a
  // crawler sees, and a meta tag added by a framework at runtime counts exactly as much as one
  // written by hand.
  function metaContent(selector, attribute) {
    var node = document.querySelector(selector);
    if (!node) return '';
    var value = attribute ? node.getAttribute(attribute) : node.getAttribute('content');
    return value ? value.trim() : '';
  }

  var meta = {
    title: (document.title || '').trim(),
    description: metaContent('meta[name="description"]'),
    lang: (document.documentElement.getAttribute('lang') || '').trim(),
    viewportContent: metaContent('meta[name="viewport"]'),
    canonical: metaContent('link[rel="canonical"]', 'href'),
    h1Count: document.querySelectorAll('h1').length,
    robots: metaContent('meta[name="robots"]'),
    ogTitle: metaContent('meta[property="og:title"]'),
    faviconHref: metaContent('link[rel~="icon"]', 'href'),
    charset: (document.characterSet || '').trim()
  };

  return JSON.stringify({
    url: location.href,
    viewportWidthPx: window.innerWidth,
    viewportHeightPx: window.innerHeight,
    documentScrollWidthPx: document.documentElement.scrollWidth,
    elements: elements,
    headings: headings,
    meta: meta,
    hasReducedMotionRule: reducedMotionRule
  });
})();
