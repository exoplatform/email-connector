/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

// What the composed body needs before it leaves the composer -- into a draft row,
// a scheduled mail or the wire -- so that what the sender saw in the editor is what
// the recipient and the Sent folder show.

// A block the editor wrote for an empty line, as its value reaches the composer. The
// editor writes an empty line as `<div>&nbsp;</div>` (Enter makes a DIV); the shared
// rich editor then replaces every `&nbsp;` by a plain space on its way out, and a
// block holding only whitespace has no height in any HTML renderer -- a mail client,
// this product's own reader -- so the empty line, and every empty line after it,
// vanished from the message. Attributes are kept as they are. A truly empty block
// (`<p></p>`) matches too, wherever it sits in the body -- a quoted or forwarded
// original included: the editor shows every empty block one line tall
// (CKEditor's `fillEmptyBlocks`, left at its default), so this is what makes the
// sent mail match what the sender saw.
const EMPTY_BLOCK = /<(div|p)(\s[^>]*)?>(?:\s|&nbsp;|&#160;)*<\/\1>/gi;

// An empty line the editor folded into the block that follows it: a new mail opens
// with an empty line above the signature, and once the user has typed, the editor
// writes that line as a blank -- `&nbsp;`, a space on its way out -- at the start of
// the block holding the signature, so the block is not empty and `EMPTY_BLOCK`
// leaves it alone, while the blank alone has no height before a block child. One
// run of blanks, read once: the marker that tells an empty line from the editor's
// own layout is looked for in the replacer (`LINE_MARKER`), not by a second
// quantifier over the same characters, which would re-read a long run of blanks
// once per character when no block follows.
const BLANK_BEFORE_CHILD = /(<(?:div|p)(?:\s[^>]*)?>)((?:\s|&nbsp;|&#160;)+)(?=<(?:div|p|table|ul|ol|blockquote|h[1-6]|pre)\b)/gi;

// What makes a run of blanks an empty line: a space or a non-breaking space before
// the run's first newline, the editor's blank once the shared rich editor has turned
// `&nbsp;` into a space on its way out. The editor writes that blank first and its
// line break after it (`<div> \n<div class="ec-signature">`); a run that opens with a
// newline is layout, the indentation of a block under its parent as a mail client
// or a newsletter writes it, whatever spaces follow the newline.
const LINE_MARKER = /^[^\r\n]*(?:[ \u00a0]|&nbsp;|&#160;)/i;

/**
 * Keeps the empty lines of a composed body: every block holding nothing but
 * whitespace gets the `<br>` that makes it one line tall everywhere, the form the
 * editor itself uses for an empty line it has just created; and a blank the editor
 * left at the start of a block, before a child block -- the empty line above the
 * signature -- becomes that same `<br>`. Running it twice changes nothing more.
 *
 * @param {string} html the body as the editor emitted it
 * @returns {string} the same body, its empty blocks made visible
 */
export function keepEmptyLines(html) {
  if (!html) {
    return html;
  }
  return html.replace(EMPTY_BLOCK, '<$1$2><br></$1>')
    .replace(BLANK_BEFORE_CHILD, (match, opening, blank) => (LINE_MARKER.test(blank) ? `${opening}<br>` : match));
}
