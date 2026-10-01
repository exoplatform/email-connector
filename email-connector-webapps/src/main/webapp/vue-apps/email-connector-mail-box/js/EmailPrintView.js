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

/*
 * The printable view of a mail or of a whole conversation (EXO-90842): one document per
 * print, its headers (from, to, cc, date, subject), each body as the reader shows it and
 * the attachment names, printed with the browser's own print from a hidden frame.
 *
 * Everything in it comes from the sender, so it is built as text first:
 * - every header value and attachment name is escaped by the DOM, never interpolated as
 *   markup;
 * - each HTML body is parsed and cleaned here (scripts, frames, forms, event attributes
 *   and script URLs removed), and remote images follow the reader's choice;
 * - each body, its own style sheets with it, lives in a shadow root of its own, inside a
 *   box that clips it and holds its fixed-position content: the sender's CSS reaches
 *   neither the header rows printed above it nor the other mails of a conversation, as
 *   the reader's own frame per mail keeps it from the portal;
 * - and the frame it prints from is sandboxed WITHOUT allow-scripts, so whatever the
 *   cleaning missed cannot run. allow-same-origin is what lets this page call print() on
 *   it and the inline (data:) images load; allow-modals is what lets the print dialog
 *   open from a sandboxed frame. The two together never include allow-scripts, the one
 *   combination that would let the frame lift its own sandbox.
 */

import { personName } from './EmailRecipientDisplay.js';

/** Elements that carry or load active content, or post somewhere: removed with their content. */
const UNSAFE_ELEMENTS = [
  'script', 'noscript', 'template', 'iframe', 'frame', 'frameset', 'object', 'embed', 'applet',
  'form', 'input', 'button', 'textarea', 'select', 'option', 'link', 'meta', 'base',
  'audio', 'video', 'source', 'track', 'portal',
  // Serialized as raw text, so a second parse of the cleaned body would read everything
  // after them -- the next mail of a conversation included -- as their text.
  'plaintext', 'xmp', 'noembed', 'noframes',
];

/** Attributes holding a URL the frame would follow or load. */
const URL_ATTRIBUTES = ['href', 'src', 'action', 'formaction', 'xlink:href', 'background', 'poster', 'srcset', 'lowsrc', 'dynsrc'];

/** Attributes that only ever hold text for a reader, never CSS. */
const TEXT_ATTRIBUTES = ['alt', 'title'];

/** URL schemes that run code or embed a document of their own. */
const SCRIPT_URL = /^\s*(javascript|vbscript|data|file):/i;

/** An inline image the server embedded in the body: the one data: URL kept. */
const INLINE_IMAGE_URL = /^\s*data:image\/(png|gif|jpe?g|webp|bmp);/i;

/** A url(...) in CSS, its argument quoted or not; what it loads is decided by isRemoteUrl. */
const CSS_URL = /url\(\s*(?:"([^"]*)"|'([^']*)'|([^)'"]*))\s*\)/gi;

/** What the URL parser removes anywhere in a URL before reading it. */
const URL_IGNORED_CHARACTERS = /[\t\n\r]/g;

/**
 * CSS that may load something a url() match cannot see: an @import (its string form needs
 * no url()), image-set() (strings again) and any backslash escape (u\72l( is url().
 * Dropped whole when remote images are not shown.
 */
const OPAQUE_CSS = /@import|image-set\(|\\/i;

/** An @import statement, its string or url() form alike. */
const CSS_IMPORT = /@import[^;]*;?/gi;

/** SVG animation elements: they can set an href to a remote URL after parsing. */
const SVG_ANIMATIONS = ['set', 'animate', 'animatemotion', 'animatetransform', 'animatecolor'];

/** The base of a mail's own shadow root, before the mail's own style sheets. */
const MAIL_CSS = `
  .ec-print-mail img { max-width: 100%; height: auto; }
  .ec-print-blocked { color: #666; font-style: italic; }
`;

/** The print view's own layout: plain, readable on paper, the mail's own styles inside it. */
const PRINT_CSS = `
  body { font-family: Roboto, Arial, sans-serif; color: #000; margin: 16px; }
  .ec-print-subject { font-size: 20px; font-weight: 500; margin: 0 0 16px; }
  .ec-print-message { page-break-inside: auto; margin-bottom: 24px; }
  .ec-print-message + .ec-print-message { border-top: 1px solid #999; padding-top: 16px; }
  .ec-print-headers { border-collapse: collapse; margin-bottom: 12px; font-size: 13px; }
  .ec-print-headers th { text-align: start; vertical-align: top; padding: 1px 12px 1px 0; white-space: nowrap; color: #444; font-weight: 500; }
  .ec-print-headers td { padding: 1px 0; word-break: break-word; }
  .ec-print-body { display: block; position: relative; overflow: hidden; contain: paint; font-size: 14px; overflow-wrap: break-word; }
  .ec-print-plain { white-space: pre-wrap; word-wrap: break-word; }
  .ec-print-attachments { margin-top: 12px; font-size: 13px; }
  .ec-print-attachments ul { margin: 4px 0 0; padding-inline-start: 20px; }
  .ec-print-blocked { color: #666; font-style: italic; }
`;

/**
 * Escapes a value for an HTML text or attribute context, through the DOM, so the sender's
 * text is shown as characters and never becomes markup.
 *
 * @param {*} value the value, anything
 * @param {Document} doc the document whose DOM escapes it
 * @returns {string} the escaped text, empty for null
 */
export function escapeHtml(value, doc = document) {
  const escaper = doc.createElement('div');
  escaper.textContent = value == null ? '' : String(value);
  return escaper.innerHTML.replace(/"/g, '&quot;');
}

/**
 * "Name <address>", or whichever of the two is known.
 *
 * @param {Object} person a sender or recipient: {name, address}
 * @returns {string} the label, as text
 */
export function addressLabel(person) {
  const name = personName(person);
  const address = person?.address?.trim() || '';
  if (name && address && name !== address) {
    return `${name} <${address}>`;
  }
  return name || address;
}

/**
 * Whether a URL loads from the network rather than from this portal or the body itself.
 * Decided the way the browser will read it -- resolved against the page the print frame
 * inherits its base from -- never by its shape: "http:host/x", "https:\\host",
 * "/\\host" or a scheme split by a line break all reach another host.
 *
 * @param {string} url the URL as written in the body
 * @param {string} origin this portal's origin, which the frame's URLs resolve against
 * @returns {boolean} true for an http(s) URL of another origin, or one the parser refuses
 */
export function isRemoteUrl(url, origin) {
  const value = (url || '').replace(URL_IGNORED_CHARACTERS, '').trim();
  if (!value) {
    return false;
  }
  try {
    const resolved = new URL(value, origin);
    return (resolved.protocol === 'http:' || resolved.protocol === 'https:') && resolved.origin !== origin;
  } catch (e) {
    return true;
  }
}

/**
 * CSS with every url() that loads from another origin replaced by none.
 *
 * @param {string} css the CSS text
 * @param {string} origin this portal's origin
 * @returns {string} the CSS without remote loads
 */
function replaceRemoteCssUrls(css, origin) {
  return (css || '').replace(CSS_URL, (match, doubleQuoted, singleQuoted, bare) => {
    const target = doubleQuoted ?? singleQuoted ?? bare;
    return isRemoteUrl(target, origin) ? 'none' : match;
  });
}

/**
 * Cleans one HTML body for the print frame: removes active and form content, event
 * attributes and script URLs, and, when the reader does not show remote images, every
 * network load of an image (src, srcset, background, CSS url()).
 *
 * @param {string} html the sender's HTML body
 * @param {Object} options {showRemoteImages, origin, blockedLabel}
 * @returns {{styles: string, body: string}} the body's own style sheets and its cleaned markup
 */
export function cleanHtmlBody(html, options = {}) {
  const origin = options.origin || window.location.origin;
  const doc = new DOMParser().parseFromString(html || '', 'text/html');
  UNSAFE_ELEMENTS.forEach(tag => doc.querySelectorAll(tag).forEach(element => element.remove()));
  // By local name: an SVG element's selector match is case-sensitive (animateMotion).
  Array.from(doc.querySelectorAll('*'))
    .filter(element => SVG_ANIMATIONS.includes(element.localName.toLowerCase()))
    .forEach(element => element.remove());
  doc.querySelectorAll('*').forEach(element => {
    Array.from(element.attributes).forEach(attribute => {
      const name = attribute.name.toLowerCase();
      if (name.startsWith('on')) {
        element.removeAttribute(attribute.name);
        return;
      }
      if (!URL_ATTRIBUTES.includes(name)) {
        // Not only style: an SVG presentation attribute (fill, stroke, mask, clip-path,
        // marker-*, filter...) is CSS too, and its url() loads an external document. Any
        // attribute but the two text ones a reader reads gets the same treatment.
        if (!options.showRemoteImages && !TEXT_ATTRIBUTES.includes(name)) {
          if (OPAQUE_CSS.test(attribute.value)) {
            element.removeAttribute(attribute.name);
          } else if (/url\(/i.test(attribute.value)) {
            element.setAttribute(attribute.name, replaceRemoteCssUrls(attribute.value, origin));
          }
        }
        return;
      }
      const value = attribute.value || '';
      const inlineImage = name === 'src' && element.tagName === 'IMG' && INLINE_IMAGE_URL.test(value);
      if (SCRIPT_URL.test(value) && !inlineImage) {
        element.removeAttribute(attribute.name);
        return;
      }
      // A link's href is followed only on a click; any other element's (SVG image,
      // feImage, use) is loaded with the page.
      const loadedWithThePage = name !== 'href' || element.localName.toLowerCase() !== 'a';
      if (!options.showRemoteImages && loadedWithThePage && (name === 'srcset' || isRemoteUrl(value, origin))) {
        element.removeAttribute(attribute.name);
        if (element.tagName === 'IMG' && name === 'src') {
          markBlocked(element, doc, options.blockedLabel);
        }
      }
    });
  });
  const styles = Array.from(doc.querySelectorAll('style'))
    .map(style => (options.showRemoteImages ? style.textContent : blockRemoteCss(style.textContent, origin)))
    .filter(Boolean)
    .map(css => `<style>${css.replace(/<\/style/gi, '<\\/style')}</style>`)
    .join('');
  doc.querySelectorAll('style').forEach(style => style.remove());
  return { styles, body: doc.body ? doc.body.innerHTML : '' };
}

/**
 * A mail style sheet with its network loads taken out: every @import, every remote url(),
 * and the whole sheet when it holds a form a url() match cannot read.
 *
 * @param {string} css the sheet's text
 * @param {string} origin this portal's origin
 * @returns {string} the sheet to keep, empty when none of it is
 */
function blockRemoteCss(css, origin) {
  const withoutImports = (css || '').replace(CSS_IMPORT, '');
  return OPAQUE_CSS.test(withoutImports) ? '' : replaceRemoteCssUrls(withoutImports, origin);
}

/**
 * Leaves a short placeholder where a blocked remote image was, its alt text kept.
 *
 * @param {Element} image the image whose source was removed
 * @param {Document} doc the body's document
 * @param {string} blockedLabel the placeholder text, in the reader's language
 * @returns {void}
 */
function markBlocked(image, doc, blockedLabel) {
  const placeholder = doc.createElement('span');
  placeholder.className = 'ec-print-blocked';
  placeholder.textContent = image.getAttribute('alt') || blockedLabel || '';
  image.replaceWith(placeholder);
}

/**
 * Whether the reader shows a message's remote images, which is what its print does too.
 * The reader shows every remote image today; a per-message or per-sender choice of the
 * reader is answered here, the one place the print view asks.
 *
 * @param {Object} message the message as the reader holds it
 * @returns {boolean} true when its remote images are shown
 */
export function readerShowsRemoteImages(message) {
  return message?.remoteImagesBlocked !== true;
}

/**
 * One message's section of the print document.
 *
 * @param {Object} message the message as the reader holds it
 * @param {Object} labels the translated labels
 * @param {Object} options {showRemoteImages: message => boolean, origin, formatDate}
 * @returns {{html: string}} the section
 */
function messageSection(message, labels, options) {
  const rows = [];
  const header = (label, value) => {
    if (value) {
      rows.push(`<tr><th>${escapeHtml(label)}</th><td>${escapeHtml(value)}</td></tr>`);
    }
  };
  header(labels.from, addressLabel(message.sender));
  header(labels.to, (message.to || []).map(addressLabel).filter(Boolean).join(', '));
  header(labels.cc, (message.cc || []).map(addressLabel).filter(Boolean).join(', '));
  header(labels.date, message.receivedDate ? options.formatDate(message.receivedDate) : '');
  header(labels.subject, message.subject || labels.noSubject);
  const content = message.content || {};
  let body;
  if (content.html === false) {
    body = `<div class="ec-print-plain">${escapeHtml(content.body)}</div>`;
  } else {
    const cleaned = cleanHtmlBody(content.body, {
      origin: options.origin,
      showRemoteImages: options.showRemoteImages(message),
      blockedLabel: labels.imageBlocked,
    });
    // Its own shadow root, declared in the markup so the frame needs no script to build
    // it: the mail's style sheets apply inside it and nowhere else. The root's host is a
    // box of its own inside the clipping one, so what a :host rule does to the host (a
    // negative margin, a transform) stays clipped to the mail's place on the page.
    body = `<div class="ec-print-host"><template shadowrootmode="open"><style>${MAIL_CSS}</style>${cleaned.styles}<div class="ec-print-mail">${cleaned.body}</div></template></div>`;
  }
  const attachments = (content.attachments || []).map(attachment => attachment?.name).filter(Boolean);
  const attachmentList = attachments.length
    ? `<div class="ec-print-attachments"><strong>${escapeHtml(labels.attachments)}</strong><ul>${attachments.map(name => `<li>${escapeHtml(name)}</li>`).join('')}</ul></div>`
    : '';
  return {
    html: `<section class="ec-print-message"><table class="ec-print-headers">${rows.join('')}</table><div class="ec-print-body">${body}</div>${attachmentList}</section>`,
  };
}

/**
 * The whole print document for one mail or a conversation, in reading order.
 *
 * @param {Array<Object>} messages the messages, each as the reader holds it (with its body)
 * @param {Object} labels the translated labels: {from, to, cc, date, subject, noSubject, attachments, imageBlocked}
 * @param {Object} options {showRemoteImages: message => whether its remote images are
 *   printed, {@link readerShowsRemoteImages} when omitted; language: the reader's
 *   language; origin}
 * @returns {string} a complete HTML document
 */
export function buildPrintDocument(messages, labels, options = {}) {
  const language = options.language || 'en';
  const dateFormat = new Intl.DateTimeFormat(language, { dateStyle: 'full', timeStyle: 'short' });
  const sectionOptions = {
    showRemoteImages: options.showRemoteImages || readerShowsRemoteImages,
    origin: options.origin || window.location.origin,
    formatDate: date => dateFormat.format(new Date(date)),
  };
  const sections = (messages || []).map(message => messageSection(message, labels, sectionOptions));
  const subject = messages?.[0]?.subject || labels.noSubject || '';
  return `<!DOCTYPE html><html lang="${escapeHtml(language)}"><head><meta charset="utf-8">`
    + `<title>${escapeHtml(subject)}</title><style>${PRINT_CSS}</style></head>`
    + `<body><h1 class="ec-print-subject">${escapeHtml(subject)}</h1>${sections.map(section => section.html).join('')}</body></html>`;
}

/**
 * Prints a document with the browser's own print, from a hidden sandboxed frame that is
 * removed once the dialog is done. The frame's load waits for its images, so a remote
 * image the reader shows is on the page when the dialog opens.
 *
 * @param {string} html the complete document
 * @returns {Promise<void>} settled once the print dialog was asked to open
 */
export function printDocument(html) {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.setAttribute('sandbox', 'allow-same-origin allow-modals');
    frame.setAttribute('aria-hidden', 'true');
    frame.setAttribute('tabindex', '-1');
    frame.setAttribute('title', 'print');
    frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;';
    let removed = false;
    const remove = () => {
      if (!removed) {
        removed = true;
        frame.remove();
      }
    };
    frame.onload = () => {
      try {
        attachDeclaredShadowRoots(frame.contentDocument);
        const printWindow = frame.contentWindow;
        printWindow.addEventListener('afterprint', remove);
        printWindow.focus();
        printWindow.print();
        // Where print() returns before the dialog closes and afterprint never comes,
        // the frame still goes, long after any dialog could need it.
        window.setTimeout(remove, 60000);
        resolve();
      } catch (e) {
        remove();
        reject(e);
      }
    };
    frame.srcdoc = html;
    document.body.appendChild(frame);
  });
}

/**
 * Builds, from this page, the shadow roots a browser without declarative shadow DOM left
 * as inert templates, so every browser prints each mail inside its own root. The frame
 * runs no script of its own; this one acts on its same-origin document.
 *
 * @param {Document} doc the print frame's document
 * @returns {void}
 */
export function attachDeclaredShadowRoots(doc) {
  if (!doc) {
    return;
  }
  doc.querySelectorAll('template[shadowrootmode]').forEach(template => {
    const host = template.parentElement;
    if (host && !host.shadowRoot) {
      host.attachShadow({ mode: 'open' }).appendChild(template.content.cloneNode(true));
    }
    template.remove();
  });
}
