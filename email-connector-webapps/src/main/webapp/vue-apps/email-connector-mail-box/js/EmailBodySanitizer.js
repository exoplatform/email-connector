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

// What a received HTML body goes through before the reader shows it. The body is
// whatever the sender wrote, markup included: nothing in it may run, reach the page
// around the frame, post a form or load another document. What stays is the
// formatting a mail is made of -- text, links, images, tables, lists, inline styles
// and the sender's own style sheets -- so the message reads as it was meant to.

/**
 * The names a purified document is cut into: the sender's style sheets, which the
 * reader places in the frame's head beside its own; the attributes the sender put on
 * the document and on the body (`dir`, `lang`, `bgcolor`, `style`...), as the
 * browser serialises them, ready to sit inside the reader's own `<html>` and `<body>`
 * tags; and the body markup.
 *
 * @typedef {{styles: string, htmlAttributes: string, bodyAttributes: string, body: string}} PurifiedBody
 */

/**
 * Tags removed on top of the HTML profile's own list. None of them is part of a
 * message: a form or a control would post from the reader's page, an embedded
 * document or player would load another origin into the frame, and the document
 * level tags belong to the frame the reader builds, not to the message.
 */
const FORBIDDEN_TAGS = [
  'form', 'input', 'button', 'select', 'textarea', 'option', 'optgroup', 'label', 'fieldset', 'legend', 'output',
  'iframe', 'frame', 'frameset', 'object', 'embed', 'applet', 'portal',
  'video', 'audio', 'source', 'track', 'canvas', 'map', 'area',
  'meta', 'link', 'base', 'title', 'noscript', 'template', 'slot', 'dialog', 'details', 'summary',
];

/**
 * Attributes removed on top of the profile's own list: the ones that make a request
 * or name a target on their own, which no formatting needs.
 */
const FORBIDDEN_ATTRIBUTES = ['action', 'formaction', 'ping', 'srcdoc', 'poster', 'background', 'usemap', 'ismap', 'autofocus', 'open'];

/**
 * The purifier's settings. The HTML profile alone: no SVG and no MathML, which carry
 * their own script surfaces and which mail does not use. The whole document is kept
 * so that a style sheet the sender put in the head survives; the reader takes it from
 * there. Data attributes are dropped: the frame runs nothing that would read them.
 */
const CONFIG = {
  USE_PROFILES: { html: true },
  WHOLE_DOCUMENT: true,
  FORBID_TAGS: FORBIDDEN_TAGS,
  FORBID_ATTR: FORBIDDEN_ATTRIBUTES,
  ALLOW_DATA_ATTR: false,
  ALLOW_UNKNOWN_PROTOCOLS: false,
};

/** The five characters that cannot be dropped into markup as themselves. */
const HTML_ESCAPES = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  '\'': '&#39;',
};

let purifier = null;

/**
 * A name an attribute may carry into the reader's tags: letters, digits and dashes,
 * which is what the purifier lets through and what a tag written by hand can hold.
 */
const ATTRIBUTE_NAME = /^[a-z][a-z0-9-]*$/i;

/**
 * The attributes of a purified element, written as they would appear in its opening
 * tag (a leading space included, or an empty string), so that the reader can put
 * them into a tag of its own. Each value is escaped here for an attribute: the four
 * characters that could end the value or the tag become entities, whatever the
 * sender wrote, and an attribute whose name is not a plain name is left behind.
 *
 * @param {Element|null} element the purified element whose attributes travel
 * @returns {string} the attributes as markup, or an empty string
 */
function serializedAttributes(element) {
  if (!element) {
    return '';
  }
  return Array.from(element.attributes)
    .filter(attribute => ATTRIBUTE_NAME.test(attribute.name))
    .map(attribute => ` ${attribute.name}="${escapeAttribute(attribute.value)}"`)
    .join('');
}

/**
 * Escapes a value so it can sit between the quotes of an attribute.
 *
 * @param {string} value the attribute value
 * @returns {string} the escaped value
 */
function escapeAttribute(value) {
  return String(value ?? '').replace(/[&<>"]/g, character => HTML_ESCAPES[character]);
}

/**
 * The purifier this module uses: an instance of its own, created once from the
 * DOMPurify factory the platform loads, and never the shared default instance. The
 * shared one carries the hooks the platform's rich-text sanitiser registered on it:
 * one rewrites every link and replaces the content of a link with more than 75
 * characters of text by that text, which flattens a linked table or a linked image
 * with its caption; another lets an iframe with any https source through. A fresh
 * instance has no hook, so this module's settings are the whole rule.
 *
 * @param {Function} [factory] the DOMPurify factory to use instead of the platform's
 *          (a harness); undefined everywhere else
 * @returns {object|null} the purifier, or null when the platform did not load DOMPurify
 */
function purifierInstance(factory) {
  if (factory) {
    return factory(window);
  }
  if (!purifier) {
    const platformFactory = typeof window !== 'undefined' ? window.DOMPurify : null;
    if (typeof platformFactory !== 'function') {
      return null;
    }
    purifier = platformFactory(window);
  }
  return purifier;
}

/**
 * Escapes text so it can be shown as text: the reader's fallback when the purifier
 * is not there, because a body that cannot be cleaned is shown as what it is, never
 * as markup.
 *
 * @param {string} text the text to escape
 * @returns {string} the escaped text
 */
function escapeHtml(text) {
  return String(text ?? '').replace(/[&<>"']/g, character => HTML_ESCAPES[character]);
}

/**
 * Purifies a received HTML body against the allow-list above, and cuts the result
 * into the sender's style sheets, the document-level attributes and the body markup.
 * <p>
 * The style sheets are returned apart because the frame the reader builds has a
 * head of its own: placed there, after the reader's base style, they apply to the
 * message exactly as they did in the sender's client, which is the one thing a
 * purifier that drops the head would have lost. A style sheet cannot run anything in
 * a frame that allows no script, so keeping it costs nothing. The attributes of the
 * sender's `<html>` and `<body>` travel the same way: a mail written right to left
 * says so on its document, and a dark mail carries its background on its body; the
 * reader's own tags take them over, purified like everything else.
 *
 * @param {string} html the body as it was received
 * @param {Function} [factory] a DOMPurify factory to use instead of the platform's
 *          (a harness); undefined everywhere else
 * @returns {PurifiedBody} the sender's style sheets and the clean body markup; with no
 *          purifier available, no styles and the body escaped as text
 */
export function sanitizeMailBody(html, factory) {
  const source = html || '';
  const instance = purifierInstance(factory);
  if (!instance) {
    return { styles: '', htmlAttributes: '', bodyAttributes: '', body: escapeHtml(source) };
  }
  const clean = instance.sanitize(source, CONFIG);
  const doc = new DOMParser().parseFromString(clean, 'text/html');
  const styles = Array.from(doc.querySelectorAll('head > style'))
    .map(sheet => sheet.textContent)
    .filter(sheet => sheet && sheet.trim())
    .join('\n');
  return {
    styles,
    htmlAttributes: serializedAttributes(doc.documentElement),
    bodyAttributes: serializedAttributes(doc.body),
    body: doc.body ? doc.body.innerHTML : '',
  };
}
