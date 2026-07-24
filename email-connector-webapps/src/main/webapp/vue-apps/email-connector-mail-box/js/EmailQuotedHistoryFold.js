/*
Copyright (C) 2025 eXo Platform SAS.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

/**
 * Gmail-style folding of the quoted history of a received mail.
 *
 * A reply usually carries the whole older thread quoted under the new message.
 * We show the latest message in full and collapse only the quoted history behind
 * a small "···" toggle, so the reader reaches the attachments row without
 * scrolling past the quoted thread.
 *
 * The body is untrusted sender markup rendered inside an isolated iframe (srcdoc).
 * This module only *hides* a suffix of that markup, it never rewrites the message:
 * on any doubt (no clear boundary, malformed HTML, parse failure) it returns the
 * original html unchanged, because hiding the real message is far worse than not
 * folding at all.
 */

/**
 * Reply-intro patterns preceding a quoted block, matched (loosely, on the trimmed
 * text of a short element) against at least English "On … wrote:" and French
 * "Le … a écrit :". The "wrote:" / "a écrit :" tail is required so that a random
 * sentence starting with On/Le is never mistaken for a quote boundary.
 */
const REPLY_INTRO_PATTERNS = [
  /^On\b[\s\S]{1,300}?\bwrote\s*:\s*$/i,
  /^Le\b[\s\S]{1,300}?\ba\s*écrit\s*:\s*$/i,
];

/**
 * Longest textContent (characters) an element may hold to still be considered a
 * one-line reply-intro rather than the quoted body itself. Keeps the end-anchored
 * intro regex from ever scanning a whole quoted thread.
 */
const MAX_INTRO_LENGTH = 400;

/**
 * The unique ids/classes of the injected wrapper and toggle, shared with the CSS
 * added by the host component and with the toggle script below.
 */
const HISTORY_ID = 'ec-quoted-history';
const TOGGLE_ID = 'ec-quoted-toggle';

/**
 * Test whether an element looks like a reply-intro line ("On … wrote:", "Le … a
 * écrit :"). Only short elements are tested so the end-anchored patterns match the
 * intro line and not the quoted body.
 *
 * @param {Element} el candidate element
 * @returns {boolean} true when the element's trimmed text is a reply-intro line
 */
function isReplyIntro(el) {
  const text = (el.textContent || '').trim();
  if (!text || text.length > MAX_INTRO_LENGTH) {
    return false;
  }
  return REPLY_INTRO_PATTERNS.some(pattern => pattern.test(text));
}

/**
 * Find the first quoted-history boundary in the parsed body, in priority order:
 *   1. a Gmail quote container (div/blockquote.gmail_quote);
 *   2. any blockquote;
 *   3. an element whose text is a reply-intro line ("… wrote:" / "… a écrit :").
 *
 * The returned node and every node after it (in the whole body) is quoted history.
 *
 * @param {Document} doc the parsed body document
 * @returns {Element|null} the boundary element, or null when nothing clearly quoted
 */
function findBoundary(doc) {
  const body = doc.body;
  if (!body) {
    return null;
  }
  const gmailQuote = body.querySelector('.gmail_quote');
  if (gmailQuote) {
    return withPrecedingIntro(gmailQuote);
  }
  const blockquote = body.querySelector('blockquote');
  if (blockquote) {
    return withPrecedingIntro(blockquote);
  }
  const candidates = body.querySelectorAll('*');
  for (let i = 0; i < candidates.length; i++) {
    if (isReplyIntro(candidates[i])) {
      return candidates[i];
    }
  }
  return null;
}

/**
 * When a quoted block (blockquote / gmail_quote) is immediately preceded, at the
 * same level, by a one-line reply-intro ("On … wrote:" / "Le … a écrit :"), fold
 * from that intro line so it collapses together with the quote — the common Apple
 * Mail / generic shape where the intro is a sibling before the blockquote. Only a
 * single, verified intro sibling is pulled in, never the real message.
 *
 * @param {Element} quote the detected quoted block
 * @returns {Element} the intro sibling when present, otherwise the quote itself
 */
function withPrecedingIntro(quote) {
  const previous = quote.previousElementSibling;
  if (previous && isReplyIntro(previous)) {
    return previous;
  }
  return quote;
}

/**
 * Lift a boundary that is nested inside wrapper elements up to the point where the
 * new message and the quoted block become siblings, without ever crossing an
 * ancestor that also holds visible content before the boundary. This lets us wrap
 * the boundary together with its following siblings while keeping the latest
 * message (which precedes it) always shown.
 *
 * @param {Element} boundary the raw boundary element
 * @param {Element} body the body element (climb stops there)
 * @returns {Element} the boundary element to wrap from
 */
function liftBoundary(boundary, body) {
  let node = boundary;
  while (node.parentNode
      && node.parentNode !== body
      && node.parentNode.nodeType === 1
      && !hasMeaningfulTextBefore(node)) {
    node = node.parentNode;
  }
  return node;
}

/**
 * Whether a node has any non-whitespace text among the siblings that precede it in
 * its parent. Used to decide it is safe to climb one level up: if content precedes
 * the boundary at this level, climbing would swallow (hide) that content.
 *
 * @param {Node} node the reference node
 * @returns {boolean} true when a preceding sibling carries visible text
 */
function hasMeaningfulTextBefore(node) {
  let sibling = node.previousSibling;
  while (sibling) {
    if ((sibling.textContent || '').trim() !== '') {
      return true;
    }
    sibling = sibling.previousSibling;
  }
  return false;
}

/**
 * Build the "See more" text toggle (collapsed state), styled like the activity
 * stream's read-more link rather than a pill.
 *
 * @param {Document} doc the owner document
 * @param {{show: string, hide: string}} labels localized link texts
 * @returns {Element} the toggle element
 */
function buildToggle(doc, labels) {
  const toggle = doc.createElement('span');
  toggle.id = TOGGLE_ID;
  toggle.className = 'ec-quoted-toggle';
  toggle.setAttribute('role', 'button');
  toggle.setAttribute('tabindex', '0');
  toggle.setAttribute('aria-expanded', 'false');
  toggle.setAttribute('aria-controls', HISTORY_ID);
  toggle.textContent = labels.show;
  return toggle;
}

/**
 * Serialize the small self-contained script wiring the toggle to the hidden
 * history container. It runs inside the iframe (srcdoc is parsed fresh, so the
 * script executes) and needs no external dependency; the host component's
 * ResizeObserver picks up the height change on expand/collapse.
 *
 * @param {{show: string, hide: string}} labels localized tooltips
 * @returns {string} the <script> markup
 */
function buildToggleScript(labels) {
  // Inner-script string literals use double quotes on purpose: this is free-form
  // text inside a single template literal (no concatenation), which keeps eslint's
  // quotes/prefer-template rules happy while the browser sees valid JS.
  // Inner-script string literals use double quotes on purpose: they are free-form
  // text inside one template literal (no concatenation), which satisfies eslint's
  // quotes/prefer-template rules while the browser sees valid JS.
  // Assembled as an array of single-quoted pieces joined with '' so the injected
  // ids/labels interleave as data; the inner-script string literals use double
  // quotes (valid JS) so nothing needs escaping. The opening/closing tags are split
  // (['<', 'script>']) so no literal </script> sits in this JS source.
  const json = JSON.stringify({ show: labels.show, hide: labels.hide }).replace(/</g, '\\u003c');
  const pieces = [
    '<', 'script>(function(){',
    'var L=', json, ';',
    'var t=document.getElementById("', TOGGLE_ID, '");',
    'var q=document.getElementById("', HISTORY_ID, '");',
    'if(!t||!q){return;}',
    'function set(open){',
    'q.style.display=open?"block":"none";',
    't.setAttribute("aria-expanded",open?"true":"false");',
    't.setAttribute("title",open?L.hide:L.show);',
    't.textContent=open?L.hide:L.show;',
    't.className="ec-quoted-toggle"+(open?" ec-open":"");',
    '}',
    't.addEventListener("click",function(){set(t.getAttribute("aria-expanded")!=="true");});',
    't.addEventListener("keydown",function(e){if(e.key==="Enter"||e.key===" "){e.preventDefault();set(t.getAttribute("aria-expanded")!=="true");}});',
    '})();<', '/script>',
  ];
  return pieces.join('');
}

/**
 * Fold the quoted history of a received mail body, if any is clearly detected.
 *
 * The boundary node and every node after it (to the end of the body) are moved
 * into a collapsed container preceded by a "···" toggle; everything before stays
 * untouched. When no boundary is found, or anything goes wrong, the original html
 * string is returned unchanged.
 *
 * @param {string} html the (sanitized) email body HTML
 * @param {{show: string, hide: string}} [labels] localized toggle tooltips
 * @param {DOMParser} [parser] an injected parser (tests); defaults to a browser one
 * @returns {string} the transformed body HTML, or the original html on no-fold
 */
export function foldQuotedHistory(html, labels, parser) {
  if (!html || typeof html !== 'string') {
    return html;
  }
  const safeLabels = {
    show: (labels && labels.show) || 'Show quoted text',
    hide: (labels && labels.hide) || 'Hide quoted text',
  };
  try {
    const dp = parser || (typeof DOMParser !== 'undefined' ? new DOMParser() : null);
    if (!dp) {
      return html;
    }
    const doc = dp.parseFromString(html, 'text/html');
    if (!doc || !doc.body) {
      return html;
    }
    const rawBoundary = findBoundary(doc);
    if (!rawBoundary) {
      return html;
    }
    const boundary = liftBoundary(rawBoundary, doc.body);
    const parent = boundary.parentNode;
    if (!parent) {
      return html;
    }
    // Collect the boundary and all of its following siblings: that is the history.
    const historyNodes = [];
    let node = boundary;
    while (node) {
      historyNodes.push(node);
      node = node.nextSibling;
    }
    const container = doc.createElement('div');
    container.id = HISTORY_ID;
    container.className = 'ec-quoted-history';
    container.style.display = 'none';
    const toggle = buildToggle(doc, safeLabels);
    parent.insertBefore(toggle, boundary);
    parent.insertBefore(container, boundary);
    historyNodes.forEach(historyNode => container.appendChild(historyNode));
    return doc.body.innerHTML + buildToggleScript(safeLabels);
  } catch (e) {
    return html;
  }
}
