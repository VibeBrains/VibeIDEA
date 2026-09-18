/*
 * Interaction probe: clicks the controls that DECLARE a state contract and reports what actually happened.
 *
 * Everything else in the design pass measures a page at rest, and a page at rest cannot answer the one question a
 * person asks of a control: does it do what it says? A header with `aria-sort` that sorts nothing, and a toggle whose
 * attribute flips while the pixels stay put, pass every resting rule there is (found by reading someone else's gates,
 * 18.09.2026).
 *
 * This one DOES touch the page — that is the point, and that is why it never runs as part of the passive measurement:
 * it is a separate, explicit action. It clicks only controls that named a contract themselves, once each, and reports
 * the attribute and the visual state before and after.
 */
(function () {
  var MAX_CONTROLS = 40;
  var CONTRACTS = ['aria-sort', 'aria-expanded', 'aria-pressed', 'aria-checked', 'aria-selected'];

  function visualOf(el) {
    var style = getComputedStyle(el);
    var rect = el.getBoundingClientRect();
    return [
      style.backgroundColor, style.color, style.borderColor, style.outlineStyle, style.textDecorationLine,
      style.fontWeight, style.opacity, style.transform,
      Math.round(rect.width), Math.round(rect.height), Math.round(rect.left), Math.round(rect.top)
    ].join('|');
  }

  function selectorOf(el) {
    if (el.id) return '#' + el.id;
    var parts = [];
    var node = el;
    var depth = 0;
    while (node && node.nodeType === 1 && depth < 4) {
      var part = node.tagName.toLowerCase();
      if (node.classList.length) part += '.' + Array.prototype.slice.call(node.classList, 0, 2).join('.');
      parts.unshift(part);
      node = node.parentElement;
      depth++;
    }
    return parts.join(' > ');
  }

  var checks = [];
  var seen = 0;
  var all = document.querySelectorAll('[' + CONTRACTS.join('],[') + ']');
  for (var i = 0; i < all.length && seen < MAX_CONTROLS; i++) {
    var el = all[i];
    var style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden') continue;
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') continue;
    var contract = null;
    for (var c = 0; c < CONTRACTS.length; c++) {
      if (el.hasAttribute(CONTRACTS[c])) { contract = CONTRACTS[c]; break; }
    }
    if (!contract) continue;
    seen++;

    var attributeBefore = el.getAttribute(contract);
    var visualBefore = visualOf(el);
    // Соседи по таблице: смысл aria-sort — в том, что меняется СОДЕРЖИМОЕ, а не сам заголовок.
    var scope = el.closest ? (el.closest('table') || el.closest('[role="grid"]') || document.body) : document.body;
    var contentBefore = (scope.textContent || '').trim().slice(0, 2000);

    try { el.click(); } catch (e) { continue; }

    checks.push({
      selector: selectorOf(el),
      tag: el.tagName.toLowerCase(),
      contract: contract,
      attributeBefore: attributeBefore,
      attributeAfter: el.getAttribute(contract),
      visualChanged: visualOf(el) !== visualBefore,
      contentChanged: ((scope.textContent || '').trim().slice(0, 2000)) !== contentBefore,
      name: (el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 80)
    });
  }

  return JSON.stringify({ url: location.href, checks: checks });
})();
