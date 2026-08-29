/*
 * Draws the findings on the page itself.
 *
 * A list of selectors in a side panel is a puzzle: the reader has to map «main > section:nth-child(2) > h1»
 * back onto what they are looking at. A frame around the thing answers it instantly.
 *
 * The overlay must be removable without a trace and must not become part of what the next
 * measurement sees, so everything lives in one container with a marker id, uses pointer-events only
 * on the labels, and never touches the page's own nodes.
 */
(function (findingsJson, pickCallbackName) {
  var CONTAINER_ID = '__vibe_design_overlay__';
  var previous = document.getElementById(CONTAINER_ID);
  if (previous) previous.remove();

  var findings;
  try { findings = JSON.parse(findingsJson); } catch (e) { return 'bad-json'; }
  if (!findings || !findings.length) return 'empty';

  var container = document.createElement('div');
  container.id = CONTAINER_ID;
  container.style.cssText = 'position:absolute;left:0;top:0;width:0;height:0;z-index:2147483000;pointer-events:none;';

  findings.forEach(function (finding) {
    var target;
    try { target = document.querySelector(finding.selector); } catch (e) { target = null; }
    if (!target) return;
    var rect = target.getBoundingClientRect();
    if (!rect.width && !rect.height) return;

    var floor = finding.floor === true;
    var color = floor ? '#e5484d' : '#f5a524';

    var box = document.createElement('div');
    box.style.cssText =
      'position:absolute;pointer-events:none;border:2px solid ' + color + ';border-radius:3px;' +
      'left:' + (rect.left + window.scrollX) + 'px;top:' + (rect.top + window.scrollY) + 'px;' +
      'width:' + rect.width + 'px;height:' + rect.height + 'px;';

    var label = document.createElement('button');
    label.type = 'button';
    label.textContent = finding.rule;
    label.title = finding.message + ' — ' + finding.evidence;
    label.style.cssText =
      'position:absolute;pointer-events:auto;cursor:pointer;border:0;border-radius:3px;' +
      'font:11px/1.4 system-ui,sans-serif;color:#fff;background:' + color + ';padding:1px 6px;' +
      'left:' + (rect.left + window.scrollX) + 'px;top:' + Math.max(0, rect.top + window.scrollY - 18) + 'px;';
    label.addEventListener('click', function (event) {
      event.preventDefault();
      event.stopPropagation();
      // Hands this one finding back to the IDE; the page itself is not modified by the click.
      if (typeof window[pickCallbackName] === 'function') window[pickCallbackName](JSON.stringify(finding));
    });

    container.appendChild(box);
    container.appendChild(label);
  });

  document.body.appendChild(container);
  return 'ok:' + container.childElementCount / 2;
})
