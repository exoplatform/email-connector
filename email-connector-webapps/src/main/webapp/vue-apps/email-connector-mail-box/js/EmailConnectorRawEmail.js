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
 * A message as the mail server holds it (EXO-90842): its source for "Show original", the
 * .eml download, and the print of a mail or a conversation. The server decides who may
 * read a source; what is addressed here is a message by its UID AND the folder that UID
 * is numbered in, the same pair every other read of this add-on sends.
 */

import { getEmailByRemoteId, isListingRow, triggerDownload } from './EmailConnectorMailBoxService.js';
import { buildPrintDocument, printDocument } from './EmailPrintView.js';

/** Event any part of the mailbox emits to open the "Show original" drawer on a message. */
export const OPEN_SOURCE_DRAWER_EVENT = 'open-email-source-drawer';

/** The fallback name of a download whose answer named none. */
const DEFAULT_EML_NAME = 'message.eml';

/**
 * The address of a message's raw source, in one of its two shapes.
 *
 * @param {Object} email the message: {mailRemoteId, folder}
 * @param {String} shape 'source' (the text, as JSON) or 'eml' (the file)
 * @returns {String} the REST address
 */
export function rawEmailUrl(email, shape) {
  const base = `/email-connector/rest/email-box/${encodeURIComponent(email.mailRemoteId)}/${shape}`;
  return email.folder ? `${base}?folder=${encodeURIComponent(email.folder)}` : base;
}

/**
 * Whether a message can be read as its source: it must be on the mail server, which a
 * draft only saved in eXo, or a row with no UID, is not.
 *
 * @param {Object} email the message
 * @returns {Boolean} true when "Show original" and the download apply
 */
export function hasRawSource(email) {
  return !!email?.mailRemoteId && !email?.scheduled;
}

/**
 * Reads a message's header block and source: {headers, source, size, truncated}.
 *
 * @param {Object} email the message: {mailRemoteId, folder}
 * @returns {Promise<Object>} the source; rejected with the HTTP status on any refusal
 */
export function getRawEmailSource(email) {
  return fetch(rawEmailUrl(email, 'source'), {
    credentials: 'include',
    method: 'GET',
  }).then(resp => {
    if (resp?.ok) {
      return resp.json();
    }
    const error = new Error('Error when reading the source of the email');
    error.status = resp?.status;
    throw error;
  });
}

/**
 * Downloads a message as a .eml file, under the name the server gave it.
 *
 * @param {Object} email the message: {mailRemoteId, folder}
 * @returns {Promise<void>} settled once the file was handed to the browser; rejected with
 *   the HTTP status on any refusal
 */
export async function downloadRawEmail(email) {
  const resp = await fetch(rawEmailUrl(email, 'eml'), {
    credentials: 'include',
    method: 'GET',
  });
  if (!resp?.ok) {
    const error = new Error('Error when downloading the email');
    error.status = resp?.status;
    throw error;
  }
  const blob = await resp.blob();
  const blobUrl = URL.createObjectURL(blob);
  triggerDownload(blobUrl, emlFileName(resp.headers.get('Content-Disposition')));
  window.setTimeout(() => URL.revokeObjectURL(blobUrl), 60000);
}

/**
 * The file name a Content-Disposition answer carries, its UTF-8 form first.
 *
 * @param {String} disposition the header, possibly null
 * @returns {String} the name, or the default one
 */
export function emlFileName(disposition) {
  const encoded = disposition && disposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (encoded) {
    try {
      return decodeURIComponent(encoded[1]);
    } catch (e) {
      // A malformed escape: the plain name below, or the default.
    }
  }
  const plain = disposition && disposition.match(/filename="([^"]+)"/i);
  return plain && plain[1] || DEFAULT_EML_NAME;
}

/**
 * The message key of the sentence a refused or failed source read is told with.
 *
 * @param {Error} error the error, carrying the HTTP status
 * @returns {String} the key
 */
export function rawEmailErrorKey(error) {
  if (error?.status === 404) {
    return 'emailConnector.mailBox.source.error.notFound';
  }
  if (error?.status === 403 || error?.status === 410) {
    return 'emailConnector.mailBox.source.error.forbidden';
  }
  return 'emailConnector.mailBox.source.error.failed';
}

/**
 * The print view's labels, in the reader's language.
 *
 * @param {Function} translate the component's $t
 * @returns {Object} the labels buildPrintDocument takes
 */
export function printLabels(translate) {
  return {
    from: translate('emailConnector.mailBox.list.drawer.detail.from'),
    to: translate('emailConnector.mailBox.list.drawer.detail.to'),
    cc: translate('emailConnector.mailBox.list.drawer.detail.cc'),
    date: translate('emailConnector.mailBox.print.date'),
    subject: translate('emailConnector.mailBox.print.subject'),
    noSubject: translate('emailConnector.mailBox.print.noSubject'),
    attachments: translate('emailConnector.mailBox.print.attachments'),
    imageBlocked: translate('emailConnector.mailBox.print.imageBlocked'),
  };
}

/**
 * Prints a mail or a conversation. A message the reader only holds as its list row (no
 * body, no recipients) is read in full first, without counting as an opening.
 *
 * @param {Array<Object>} messages the messages to print, in reading order
 * @param {Object} labels the translated labels of the print view
 * @param {String} language the reader's language
 * @returns {Promise<void>} settled once the print dialog was asked to open
 */
export async function printEmails(messages, labels, language) {
  const full = await Promise.all((messages || []).map(message => (isListingRow(message)
    ? getEmailByRemoteId(message.mailRemoteId, message.folder, { broadcast: false })
    : message)));
  await printDocument(buildPrintDocument(full, labels, { language }));
}
