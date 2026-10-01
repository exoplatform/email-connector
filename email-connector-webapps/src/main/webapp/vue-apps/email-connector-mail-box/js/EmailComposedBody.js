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

/**
 * Keeps the empty lines of a composed body: every block holding nothing but
 * whitespace gets the `<br>` that makes it one line tall everywhere, the form the
 * editor itself uses for an empty line it has just created.
 *
 * @param {string} html the body as the editor emitted it
 * @returns {string} the same body, its empty blocks made visible
 */
export function keepEmptyLines(html) {
  if (!html) {
    return html;
  }
  return html.replace(EMPTY_BLOCK, '<$1$2><br></$1>');
}
