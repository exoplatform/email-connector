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
 * Markers that a quoted block is a *forwarded* message rather than a reply's quoted
 * history. A forward is content the sender chose to include, so it must stay visible
 * — we never fold it behind "See more". Reply markers ("On … wrote:", Outlook's
 * "Original Message") are intentionally NOT here.
 *
 * The marker text comes from the *sender's* mail client locale, not the reader's UI
 * language, so English/French alone missed forwards from senders using other mail
 * clients' languages — they fell through to the generic blockquote/gmail_quote check
 * below and got folded, which is exactly what must never happen to a forward. This
 * literal-string list is necessarily best-effort: it covers this addon's major
 * supported locales (see locale/portlet/emailConnector) but can't be exhaustive over
 * every mail-client / language combination — extend it as gaps are reported.
 */
const FORWARD_MARKERS = [
  /-+\s*Forwarded message\s*-+/i,
  /Begin forwarded message\s*:/i,
  /-+\s*Message transféré\s*-+/i,
  /-+\s*Weitergeleitete Nachricht\s*-+/i,
  /-+\s*Mensaje reenviado\s*-+/i,
  /-+\s*Messaggio inoltrato\s*-+/i,
  /-+\s*Mensagem (reencaminhada|encaminhada)\s*-+/i,
  /-+\s*Doorgestuurd bericht\s*-+/i,
  /-+\s*Пересланное сообщение\s*-+/i,
  /-+\s*Przekazana wiadomość\s*-+/i,
];

/**
 * Whether an element's text carries a forwarded-message marker.
 *
 * @param {Element} el candidate element
 * @returns {boolean} true when the element looks like a forwarded message
 */
function isForwarded(el) {
  const text = el.textContent || '';
  return FORWARD_MARKERS.some(pattern => pattern.test(text));
}

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
  // A forwarded message is shown in full, never folded — so if the body carries a
  // forward marker we treat it as having no foldable quoted history at all.
  if (isForwarded(body)) {
    return null;
  }
  // Outlook wraps the quoted reply — its "De:/From:" header AND the original body —
  // in a #divRplyFwdMsg. Fold from it (or the <hr> that precedes it) so the whole
  // previous message collapses, header included, rather than only the inner quote.
  const outlookReply = doc.getElementById('divRplyFwdMsg');
  if (outlookReply) {
    const previous = outlookReply.previousElementSibling;
    return previous && previous.tagName === 'HR' ? previous : outlookReply;
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
 * Whether anything the reader can actually see survives outside the collapsed block.
 * <p>
 * The boundary is only ever a *guess*, and a wrong one that leaves nothing visible
 * turns the mail into an empty frame with a "See more" link — the module's one stated
 * red line. It happens whenever the boundary is the first thing in the body: a mail
 * whose whole content is the attribution line, or an intro immediately followed by
 * its quote. Cheaper to detect afterwards than to enumerate the shapes that cause it.
 * <p>
 * Script and style text is stripped before looking, since it counts as text content
 * while rendering nothing; an image alone, on the other hand, is a real message.
 *
 * @param {Element} body the transformed body element
 * @returns {boolean} true when the reader is still left with something to read
 */
function hasVisibleContentOutsideHistory(body) {
  const clone = body.cloneNode(true);
  const history = clone.querySelector(`#${HISTORY_ID}`);
  if (history) {
    history.remove();
  }
  const toggle = clone.querySelector(`#${TOGGLE_ID}`);
  if (toggle) {
    toggle.remove();
  }
  clone.querySelectorAll('script, style').forEach(node => node.remove());
  if ((clone.textContent || '').trim() !== '') {
    return true;
  }
  return clone.querySelector('img') !== null;
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
    if (!hasVisibleContentOutsideHistory(doc.body)) {
      return html;
    }
    return doc.body.innerHTML + buildToggleScript(safeLabels);
  } catch (e) {
    return html;
  }
}

/**
 * The text a message carries above its quoted history — what its author actually
 * wrote — collapsed onto one line for a preview.
 *
 * A one-line preview exists to answer "which message is this", and since a reply now
 * opens with the message it answers quoted under the cursor, the raw body of every
 * unfinished reply in a conversation begins with the same words. Taking the body's
 * first line would make each of them read like the mail being replied to; what tells
 * them apart is the sentence above the quote, and that is all a preview has room for.
 *
 * The boundary is found by exactly the rules the fold above uses, so the preview and
 * the reader can never disagree about where a quote starts. Including the default:
 * on no clear boundary, or on any trouble, the whole body is shown. An untidy
 * preview is a nuisance; a preview that lost the words is a defect.
 *
 * @param {string} html the body markup
 * @param {DOMParser} [parser] an injected parser (tests); defaults to a browser one
 * @returns {string} the whitespace-collapsed text written above the quote
 */
export function unquotedText(html, parser) {
  if (!html || typeof html !== 'string') {
    return '';
  }
  const dp = parser || (typeof DOMParser !== 'undefined' ? new DOMParser() : null);
  if (!dp) {
    return '';
  }
  let doc = null;
  try {
    doc = dp.parseFromString(html, 'text/html');
  } catch (e) {
    return '';
  }
  if (!doc || !doc.body) {
    return '';
  }
  try {
    removeQuotedHistory(doc);
  } catch (e) {
    // Deliberately silent, and deliberately not a rethrow: whatever went wrong, the
    // body is still parsed and its text is still worth showing.
  }
  return (doc.body.textContent || '').replace(/\s+/g, ' ').trim();
}

/**
 * Strips the quoted history — the boundary node and every node after it — out of a
 * parsed body, leaving what was written above it. Does nothing when no boundary is
 * clearly detected, which is how "no quote here" and "not sure" reach the same,
 * safe answer.
 *
 * @param {Document} doc the parsed body document
 * @returns {void}
 */
function removeQuotedHistory(doc) {
  const rawBoundary = findBoundary(doc);
  if (!rawBoundary) {
    return;
  }
  let node = liftBoundary(rawBoundary, doc.body);
  while (node) {
    const next = node.nextSibling;
    node.parentNode.removeChild(node);
    node = next;
  }
}
