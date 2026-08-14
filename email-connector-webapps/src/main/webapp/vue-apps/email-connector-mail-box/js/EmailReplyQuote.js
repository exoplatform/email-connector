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
 * The body a reply opens with: room to write at the top, and the message being
 * answered quoted underneath — the way every mail client has done it since mail had
 * clients, because a reply read on its own says nothing about what it answers.
 *
 * The markup is NOT free-form. It is deliberately the shape this product's own
 * reader already knows how to collapse (see EmailQuotedHistoryFold): a
 * `gmail_quote` container holding the attribution line and a `blockquote`. Emitting
 * exactly that shape means the sent copy, once the sync brings it back into the
 * conversation, folds behind the "Show quoted text" toggle by itself — the reply
 * reads as the two sentences the user wrote, not as the whole thread they wrote
 * them under. Nothing else had to be built for that to happen; matching the shape
 * IS the feature.
 *
 * `gmail_quote` is a Gmail class name in a product that is not Gmail, and that is
 * on purpose: it is the one quote marker the whole ecosystem agreed on, emitted by
 * every client that wants Gmail to collapse its quotes and recognised by every
 * client that collapses theirs. `ec-quote` rides alongside it so that a reader of
 * this markup — ours or anyone's — can see where it came from.
 */

/**
 * The classes on the quote container and on the blockquote inside it. `gmail_quote`
 * is what the reader's fold looks for first, and what other mail clients look for;
 * `ec-quote` says whose composer wrote it.
 */
const QUOTE_CLASS = 'gmail_quote ec-quote';

/**
 * The class on the attribution line, again Gmail's, so that a client which strips
 * unknown wrappers but keeps `gmail_attr` still shows the line above the quote.
 */
const ATTRIBUTION_CLASS = 'gmail_attr';

/**
 * The bar-and-indent every mail client renders a quote with, written inline because
 * a mail body travels without a stylesheet: whatever is not in the `style`
 * attribute is not there at all on the other side.
 */
const BLOCKQUOTE_STYLE = 'margin:0 0 0 .8ex;border-left:1px solid #ccc;padding-left:1ex';

/**
 * Tags whose presence means a stored body is markup rather than the plain text it
 * arrived as. The two genuinely occur: a message is cached exactly as it was
 * received, and a multipart with no text/html part is stored as its text/plain one
 * (see EmailConnectorUtils#getHtmlFromMimeMultipart), with no `html` flag surviving
 * to this side to tell us which we are holding.
 *
 * A named list rather than "anything between angle brackets", because the one thing
 * plain-text mail is full of is angle brackets around addresses — `<bob@acme.com>`
 * would answer yes to the loose test and get a plain-text body quoted as if it were
 * HTML, i.e. rendered with its line breaks thrown away.
 */
const HTML_BODY_PATTERN =
  /<\/?(?:a|b|blockquote|body|br|center|div|em|font|h[1-6]|hr|html|i|img|li|ol|p|pre|span|strong|table|tbody|td|th|tr|u|ul)\b[^>]*>/i;

/** The five characters that cannot be dropped into markup as themselves. */
const HTML_ESCAPES = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  '\'': '&#39;',
};

/**
 * Escapes text so it can be dropped into markup as text — a sender's display name,
 * their address, the date. None of these is ours: a name arrives from whatever
 * wrote the message, and the composer is about to hand it to an HTML editor.
 *
 * @param {string} text - the text to escape
 * @returns {string} the escaped text
 */
export function escapeHtml(text) {
  return String(text ?? '').replace(/[&<>"']/g, character => HTML_ESCAPES[character]);
}

/**
 * Whether a stored body is HTML markup rather than the plain text it arrived as.
 *
 * @param {string} body - the body as it was received and cached
 * @returns {boolean} true when the body is markup
 */
export function isHtmlBody(body) {
  return HTML_BODY_PATTERN.test(body || '');
}

/**
 * The original message, ready to sit inside the quote container.
 *
 * The two kinds of body need opposite treatment, and getting it the wrong way round
 * is visible immediately:
 *
 * - HTML is passed through untouched. It is already markup, it is about to be shown
 *   inside a blockquote that gives it the bar and the indent, and rewriting a
 *   sender's markup to quote it would be this composer editing somebody else's
 *   message.
 *
 * - Plain text is escaped and given the `> ` prefixes that have meant "quoted" since
 *   before HTML mail existed, with its line breaks turned into `<br>` so they
 *   survive. Dropped into the editor as-is it would render as one long paragraph:
 *   HTML collapses the newlines that WERE the message's structure. The prefixes are
 *   belt as well as braces — they are the quoting a correspondent reading in plain
 *   text will see, once the blockquote around them has been flattened away.
 *
 * @param {string} body - the original message body, as it was received
 * @returns {string} the markup to quote, or an empty string when there is nothing
 */
export function quotedOriginalBody(body) {
  const original = (body || '').trim();
  if (!original) {
    return '';
  }
  if (isHtmlBody(original)) {
    return original;
  }
  return original.split(/\r\n|\r|\n/)
    .map(line => {
      const escaped = escapeHtml(line);
      // A blank line keeps its marker and nothing else: a trailing space after the
      // ">" is invisible here and reads as trailing whitespace everywhere else.
      return escaped ? `&gt; ${escaped}` : '&gt;';
    })
    .join('<br>');
}

/**
 * The whole body a reply opens with.
 *
 * The two leading breaks are the point of the exercise: the editor puts the caret at
 * the start of its content, so what the user meets is an empty line with the quote
 * below it, not a cursor jammed against somebody else's words.
 *
 * Returns nothing at all when there is nothing to quote — a message whose body did
 * not survive, or one that never had one. An attribution line over an empty
 * blockquote is a stray vertical bar and a claim about a message we cannot show;
 * a reply that simply opens empty is the older, honest behaviour.
 *
 * @param {string} attribution - the localized "On <date>, <sender> wrote:" line,
 *          already escaped by its caller, which is the only part of this block whose
 *          wording belongs to the UI rather than to the mail
 * @param {string} originalBody - the original message body, as it was received
 * @returns {string} the composer's starting body, or an empty string
 */
export function replyQuoteBody(attribution, originalBody) {
  const quoted = quotedOriginalBody(originalBody);
  if (!quoted) {
    return '';
  }
  return [
    '<br><br>',
    `<div class="${QUOTE_CLASS}">`,
    `<div class="${ATTRIBUTION_CLASS}">${attribution}<br></div>`,
    `<blockquote class="${QUOTE_CLASS}" style="${BLOCKQUOTE_STYLE}">`,
    quoted,
    '</blockquote>',
    '</div>',
  ].join('');
}
