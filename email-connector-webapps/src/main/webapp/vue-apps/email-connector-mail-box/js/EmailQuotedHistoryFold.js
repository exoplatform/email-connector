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
 * Markers that a block is a *forwarded* message.
 *
 * A forward is only protected from the fold when it is what the sender chose to send.
 * Whether it is depends on *where* it sits, not on its being there at all: a forward at
 * the head of the body is the message and stays whole, while a forward the sender is
 * replying under is quoted history like any other and folds with it. Judging by presence
 * alone is what stopped "Re: Fwd: …" threads folding at all — the reply's own history
 * carried the marker, and the whole body was spared. See {@link forwardIsTheMessage}.
 *
 * Reply markers ("On … wrote:", Outlook's "Original Message") are intentionally NOT here.
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
 * How many consecutive source lines a plain-text attribution may span. Mail clients
 * wrap it when the address is long ("On … Gianni Falzone\n<gianni@example.org> wrote:"),
 * so testing one line at a time would miss exactly the replies people actually send.
 */
const MAX_INTRO_LINES = 3;

/**
 * A line of quoted history, in the convention plain-text mail has always used: one or
 * more "&gt;" markers, possibly indented. Nesting ("&gt; &gt;") is just more of them.
 */
const QUOTED_LINE_PATTERN = /^\s*>/;

/**
 * The unique ids/classes of the injected wrapper and toggle, shared with the CSS
 * added by the host component and with the toggle script below.
 */
const HISTORY_ID = 'ec-quoted-history';
const TOGGLE_ID = 'ec-quoted-toggle';

/**
 * Outlook's container for the quoted previous message — its "De:/From:" header block and
 * the original body together. Outlook writes no "On … wrote:" line, so this id is the
 * only thing that says "a reply's history starts here" in mail composed with it.
 */
const OUTLOOK_REPLY_ID = 'divRplyFwdMsg';

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
 *   1. the outermost quoted block (a Gmail quote container or any blockquote);
 *   2. an element whose text is a reply-intro line ("… wrote:" / "… a écrit :").
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
  // The forward check used to sit here, refusing a boundary for any body carrying a
  // marker anywhere. It now runs on the finished fold instead ({@link forwardIsTheMessage}),
  // because whether a forward may be folded is a question about its position, and that
  // is only answerable once the boundary is known.
  // Outlook wraps the quoted reply — its "De:/From:" header AND the original body —
  // in a #divRplyFwdMsg. Fold from it (or the <hr> that precedes it) so the whole
  // previous message collapses, header included, rather than only the inner quote.
  const outlookReply = doc.getElementById(OUTLOOK_REPLY_ID);
  if (outlookReply) {
    const previous = outlookReply.previousElementSibling;
    return previous && previous.tagName === 'HR' ? previous : outlookReply;
  }
  // The outermost quoted block, whichever kind it is — taken in document order rather
  // than by asking for a gmail_quote first and a blockquote only if there is none. A
  // "Re: Fwd:" is where that distinction shows: the forwarded message inside the quoted
  // history is itself a gmail_quote, so preferring the class found the *innermost* block
  // and folded from inside the history instead of from the top of it.
  const quote = body.querySelector('.gmail_quote, blockquote');
  if (quote) {
    return withPrecedingIntro(quote);
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
  const clone = withoutHistory(body);
  if ((clone.textContent || '').trim() !== '') {
    return true;
  }
  return clone.querySelector('img') !== null;
}

/**
 * A copy of the transformed body holding only what the reader is left looking at: the
 * collapsed block, the toggle and the reader's own chrome removed.
 *
 * @param {Element} body the transformed body element
 * @returns {Element} the detached copy
 */
function withoutHistory(body) {
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
  return clone;
}

/**
 * Whether the boundary about to be folded from opens a *reply's* quoted history, as
 * opposed to being a quoted block of no stated provenance.
 * <p>
 * This is what separates the two things a forward marker can mean. A forward under a
 * reply attribution is the thread the sender is answering; a forward with no reply
 * above it is the message they chose to send. The attribution is the signal, and it is
 * always verified by {@link isReplyIntro} — the same end-anchored, length-limited test
 * used everywhere here — so a sentence merely mentioning a forward can never pass for
 * one.
 * <p>
 * Where mail clients put that attribution differs, and all three placements count: the
 * element *is* the intro (the generic scan, and the Apple Mail sibling that
 * {@link withPrecedingIntro} already pulled in); the intro is the quote block's first
 * child (Gmail's {@code gmail_attr}); or the boundary is Outlook's reply container,
 * whose "De:/From:" header block plays the same role.
 *
 * @param {Element} boundary the boundary element the fold starts at
 * @returns {boolean} true when a reply attribution introduces the collapsed block
 */
function isReplyLedBoundary(boundary) {
  if (isReplyIntro(boundary)) {
    return true;
  }
  if (boundary.id === OUTLOOK_REPLY_ID
      || (boundary.nextElementSibling && boundary.nextElementSibling.id === OUTLOOK_REPLY_ID)) {
    return true;
  }
  const first = boundary.firstElementChild;
  return !!first && isReplyIntro(first);
}

/**
 * Whether the forwarded message this body carries is the message itself, in which case
 * the fold is dropped and the mail shown whole.
 * <p>
 * The product rule, in one line: a forward folds when it is past the sender's message,
 * in the history. Two things have to hold for that, and each rules out one way of being
 * wrong. The block must be introduced by a reply attribution — otherwise there is no
 * history for the forward to be "past", and the forwarded content is simply what was
 * sent, note above it or not. And no marker may be left in what stays visible — a
 * forward the reader can still see is one the sender included, and folding from some
 * attribution deeper inside it would hide the message behind its own header.
 * <p>
 * A note above a forward therefore keeps it whole, deliberately. Grading how much prose
 * counts as "a real note" would be an invented threshold, and the note is *about* the
 * forward ("voir ci-dessous") — hiding what it points at is the expensive mistake, while
 * showing a little more than necessary is the cheap one.
 *
 * @param {Element} body the transformed body element
 * @param {Element} boundary the boundary the fold started at
 * @returns {boolean} true when the fold must be dropped
 */
function forwardIsTheMessage(body, boundary) {
  if (!isForwarded(body)) {
    return false;
  }
  return !isReplyLedBoundary(boundary) || isForwarded(withoutHistory(body));
}

/**
 * Fill in the default toggle texts, so a caller that has no bundle at hand still gets a
 * usable toggle rather than an empty one.
 *
 * @param {{show: string, hide: string}} [labels] localized link texts
 * @returns {{show: string, hide: string}} the texts to render
 */
function toSafeLabels(labels) {
  return {
    show: (labels && labels.show) || 'Show quoted text',
    hide: (labels && labels.hide) || 'Hide quoted text',
  };
}

/**
 * Where the quoted history starts in a plain-text body, as the line index of its
 * attribution line.
 * <p>
 * Two things have to hold, and the second is what keeps this from folding the message
 * itself: a line (or up to {@link MAX_INTRO_LINES} of them, since clients wrap it) reads
 * as a reply-intro, and the next line carrying anything is quoted. An attribution with
 * nothing quoted under it is a sentence, not a boundary — somebody writing "as Bob wrote:"
 * before quoting nothing at all.
 * <p>
 * The lines forming the intro must not themselves be quoted, or a one-level-deeper
 * "&gt; he wrote:" could be joined to the real message's last line and pass for one.
 *
 * @param {string[]} lines the body's lines
 * @returns {number} the attribution's line index, or -1 when nothing is clearly quoted
 */
function findPlainTextBoundary(lines) {
  for (let start = 0; start < lines.length; start++) {
    if (QUOTED_LINE_PATTERN.test(lines[start])) {
      continue;
    }
    for (let span = 1; span <= MAX_INTRO_LINES && start + span <= lines.length; span++) {
      if (QUOTED_LINE_PATTERN.test(lines[start + span - 1])) {
        break;
      }
      const intro = lines.slice(start, start + span).join('\n').trim();
      if (intro.length > MAX_INTRO_LENGTH) {
        break;
      }
      if (intro && REPLY_INTRO_PATTERNS.some(pattern => pattern.test(intro)) && quotesSomething(lines, start + span)) {
        return start;
      }
    }
  }
  return -1;
}

/**
 * The line a forwarded message is announced on, wherever the "&gt;" markers of a quote
 * put it — a forward quoted inside a reply's history carries them, one at the head of the
 * body does not, and that difference is exactly what the caller compares against the
 * boundary.
 *
 * @param {string[]} lines the body's lines
 * @returns {number} the marker's line index, or -1 when the body announces no forward
 */
function firstForwardLine(lines) {
  return lines.findIndex(line => FORWARD_MARKERS.some(pattern => pattern.test(line)));
}

/**
 * Whether the first line carrying anything, from the given position on, is a quoted one.
 * Blank lines are skipped because every client leaves one between the attribution and the
 * quote it introduces.
 *
 * @param {string[]} lines the body's lines
 * @param {number} from the first line to look at
 * @returns {boolean} true when a quoted block follows
 */
function quotesSomething(lines, from) {
  for (let index = from; index < lines.length; index++) {
    if (lines[index].trim() !== '') {
      return QUOTED_LINE_PATTERN.test(lines[index]);
    }
  }
  return false;
}

/**
 * Build one preformatted block of plain text. The text goes in as text, never as markup:
 * this is the sender's message, so a line reading "&lt;script&gt;" has to show those
 * characters.
 *
 * @param {Document} doc the owner document
 * @param {string} text the block's text
 * @param {string} [id] the element id, for the collapsible one
 * @returns {Element} the block
 */
function buildPlainTextBlock(doc, text, id) {
  const block = doc.createElement('div');
  block.className = id ? 'ec-plain-text ec-quoted-history' : 'ec-plain-text';
  if (id) {
    block.id = id;
  }
  block.textContent = text;
  return block;
}

/**
 * Fold the quoted history of a plain-text mail body.
 * <p>
 * The markup fold below has nothing to work with here: a plain-text reply quotes with an
 * attribution line and a run of "&gt;"-prefixed lines, and carries no blockquote, no
 * gmail_quote, no element at all. So the whole history was shown, every time, on exactly
 * the shape we now generate ourselves for plain-text replies.
 * <p>
 * Same red line as the markup fold: a fold that would leave the reader nothing to read is
 * not worth having, so a boundary with only blank lines before it is discarded and the
 * caller shows the message whole. A forwarded message is never folded either — it is
 * content the sender chose to include.
 *
 * @param {string} text the plain-text email body
 * @param {{show: string, hide: string}} [labels] localized toggle texts
 * @param {DOMParser} [parser] an injected parser (tests); defaults to a browser one
 * @returns {string|null} the folded body markup, or null when there is nothing to fold
 */
export function foldPlainTextQuotedHistory(text, labels, parser) {
  if (!text || typeof text !== 'string') {
    return null;
  }
  try {
    const dp = parser || (typeof DOMParser !== 'undefined' ? new DOMParser() : null);
    if (!dp) {
      return null;
    }
    const lines = text.split('\n');
    const boundary = findPlainTextBoundary(lines);
    if (boundary < 0) {
      return null;
    }
    // The same forward rule the markup path applies, and it lands in the same place for
    // the same reason: a marker below the attribution is inside the history being folded,
    // a marker above it is the message the sender sent. The plain-text boundary is always
    // a reply attribution by construction, so position is the whole test here — there is
    // no "is this a reply's history at all" left to ask.
    const forwardLine = firstForwardLine(lines);
    if (forwardLine >= 0 && forwardLine < boundary) {
      return null;
    }
    // Trailing blank lines belong to the gap the sender left before the quote, and
    // pre-wrap would render every one of them above the toggle.
    const visible = lines.slice(0, boundary).join('\n').replace(/\s+$/, '');
    if (visible === '') {
      return null;
    }
    const doc = dp.parseFromString('<!doctype html><html><body></body></html>', 'text/html');
    if (!doc || !doc.body) {
      return null;
    }
    const safeLabels = toSafeLabels(labels);
    const history = buildPlainTextBlock(doc, lines.slice(boundary).join('\n'), HISTORY_ID);
    history.style.display = 'none';
    doc.body.appendChild(buildPlainTextBlock(doc, visible));
    doc.body.appendChild(buildToggle(doc, safeLabels));
    doc.body.appendChild(history);
    return doc.body.innerHTML + buildToggleScript(safeLabels);
  } catch (e) {
    // Same rule as everywhere here: on any doubt, the message is shown untouched.
    return null;
  }
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
  const safeLabels = toSafeLabels(labels);
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
    // Checked after the fold rather than before a boundary is looked for: "is this
    // forward past the sender's message" is a question about position, and there is no
    // position to speak of until the boundary is known. The two checks cannot disagree —
    // this one is about a forward, the one above about there being anything left to read.
    if (forwardIsTheMessage(doc.body, rawBoundary)) {
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
