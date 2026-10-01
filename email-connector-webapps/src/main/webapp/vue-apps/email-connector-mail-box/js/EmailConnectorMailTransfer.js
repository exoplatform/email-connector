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
 * Mail in and out of the mailbox as files: the selection as a .zip of .eml and a folder
 * as an .mbox (EXO-90845), and .eml, .zip or .mbox files imported into a folder
 * (EXO-90846). The server decides who may read or write; what is decided here is only
 * where the actions are offered, by the same predicates the other folder actions use.
 *
 * An export is checked first (a small JSON answer carrying the count and the cap the
 * server holds -- the interface keeps no copy of it), then downloaded by the browser
 * itself from the REST address, so a large file streams to disk and never sits in the
 * page's memory.
 */

import { isScheduledView, triggerDownload } from './EmailConnectorMailBoxService.js';
import { parseSelectionKey } from './EmailConnectorMailBoxSelection.js';
import { isSharedMailboxFolder, sharedFolderRole, sharedMailboxAllows } from './EmailConnectorSharedMailboxes.js';

/** Event any part of the mailbox emits to open the import drawer: {folder, files}. */
export const OPEN_IMPORT_DRAWER_EVENT = 'open-email-import-drawer';

/** Event the import drawer emits when an import ended: {folder}. */
export const IMPORT_FINISHED_EVENT = 'email-import-finished';

const BASE_URL = '/email-connector/rest/email-box';

// The user's own folders mail is imported into: not Drafts (authored here), Trash and
// Spam (their own meaning), the Scheduled view or All Mail (no folder) -- the server's
// EmailBoxService#checkImportTarget.
const OWN_IMPORT_FOLDERS = ['INBOX', 'SENT', 'ARCHIVE'];

// The roles of a shared mailbox's folders mail is not imported into, for the same reasons.
const SHARED_NO_IMPORT_ROLES = ['DRAFTS', 'TRASH', 'JUNK'];

// The folder keys that are not folders an export can read whole.
const NOT_EXPORTABLE_FOLDERS = ['SCHEDULED', 'ALL_MAIL'];

/**
 * Whether a selection can be downloaded as a .zip: something is selected and no draft
 * is, a draft saved in eXo having no source on the mail server.
 *
 * @param {Array<String>} keys the selection keys
 * @returns {Boolean} true when the download is offered
 */
export function canDownloadSelection(keys) {
  return !!keys?.length && keys.every(key => parseSelectionKey(key).id > 0);
}

/**
 * Whether a folder can be exported whole: a real folder, and in a shared mailbox one the
 * user may read.
 *
 * @param {String} folder the folder key
 * @returns {Boolean} true when the export is offered
 */
export function canExportFolder(folder) {
  const key = folder || 'INBOX';
  return !NOT_EXPORTABLE_FOLDERS.includes(key) && !isScheduledView(key) && sharedMailboxAllows(key, 'read');
}

/**
 * Whether mail can be imported into a folder: Inbox, Sent, Archive or a folder of the
 * user's own; in a shared mailbox, a folder they may insert into ("moveTarget", the i
 * right) that is not its Drafts, Trash or Spam.
 *
 * @param {String} folder the folder key
 * @returns {Boolean} true when the import is offered
 */
export function canImportInto(folder) {
  const key = folder || 'INBOX';
  if (isSharedMailboxFolder(key)) {
    return sharedMailboxAllows(key, 'moveTarget') && !SHARED_NO_IMPORT_ROLES.includes(sharedFolderRole(key));
  }
  return OWN_IMPORT_FOLDERS.includes(key) || String(key).startsWith('CUSTOM:');
}

/**
 * Downloads a selection as a .zip, after the server checked it: nothing is downloaded
 * when it holds more than a .zip may, or a message the user may not read.
 *
 * @param {Array<String>} keys the selection keys
 * @returns {Promise<Object>} the check {count, max}; rejected with the HTTP status on a
 *   refusal, or with `tooMany` set (and the cap in `max`) past the cap
 */
export async function downloadSelectionZip(keys) {
  const query = keys.map(key => `mails=${encodeURIComponent(key)}`).join('&');
  const check = await getJson(`${BASE_URL}/export/zip/check?${query}`);
  if (check.count > check.max) {
    throw tooMany(check);
  }
  triggerDownload(`${BASE_URL}/export/zip?${query}`, 'emails.zip');
  return check;
}

/**
 * Downloads a folder as an .mbox, after the server checked it.
 *
 * @param {String} folder the folder key
 * @returns {Promise<Object>} the check {count, max}; rejected with the HTTP status on a
 *   refusal, or with `tooMany` set (and the cap in `max`) past the cap
 */
export async function downloadFolderMbox(folder) {
  const query = `folder=${encodeURIComponent(folder || 'INBOX')}`;
  const check = await getJson(`${BASE_URL}/export/mbox/check?${query}`);
  if (check.count > check.max) {
    throw tooMany(check);
  }
  triggerDownload(`${BASE_URL}/export/mbox?${query}`, 'mailbox.mbox');
  return check;
}

/**
 * The message key of the sentence a refused or failed export is told with.
 *
 * @param {Error} error the error: `tooMany`, or the HTTP status
 * @returns {String} the key
 */
export function exportErrorKey(error) {
  if (error?.tooMany) {
    return 'emailConnector.mailBox.export.error.tooMany';
  }
  if (error?.status === 404) {
    return 'emailConnector.mailBox.export.error.notFound';
  }
  if (error?.status === 403 || error?.status === 410) {
    return 'emailConnector.mailBox.export.error.forbidden';
  }
  return 'emailConnector.mailBox.export.error.failed';
}

/**
 * Starts an import of uploaded files into a folder.
 *
 * @param {String} folder the folder key
 * @param {Array<String>} uploadIds the uploads holding the files
 * @returns {Promise<Object>} the import state; rejected with the HTTP status and the
 *   server's message code
 */
export async function startImport(folder, uploadIds) {
  const resp = await fetch(`${BASE_URL}/import`, {
    credentials: 'include',
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ folder, uploadIds }),
  });
  if (resp?.ok) {
    return resp.json();
  }
  const error = new Error('Error when starting the import');
  error.status = resp?.status;
  error.code = await messageCode(resp);
  throw error;
}

/**
 * How the user's import is going, or went, with the limits an import takes.
 *
 * @returns {Promise<Object>} the import state
 */
export function getImportStatus() {
  return getJson(`${BASE_URL}/import/status`);
}

/**
 * Reads a JSON answer.
 *
 * @param {String} url the address
 * @returns {Promise<Object>} the answer; rejected with the HTTP status
 */
async function getJson(url) {
  const resp = await fetch(url, { credentials: 'include', method: 'GET' });
  if (resp?.ok) {
    return resp.json();
  }
  const error = new Error('Error when reading the mailbox');
  error.status = resp?.status;
  throw error;
}

/**
 * The error of an export past its cap.
 *
 * @param {Object} check the server's check {count, max}
 * @returns {Error} the error, `tooMany` set, the cap in `max`
 */
function tooMany(check) {
  const error = new Error('Too many emails for one file');
  error.tooMany = true;
  error.max = check.max;
  return error;
}

/**
 * The message code a refused request answered with, when it answered one.
 *
 * @param {Response} resp the answer
 * @returns {Promise<String>} the code, or null
 */
async function messageCode(resp) {
  try {
    const text = await resp.text();
    if (!text) {
      return null;
    }
    try {
      const body = JSON.parse(text);
      return body?.message || body?.detail || null;
    } catch (e) {
      return text;
    }
  } catch (e) {
    return null;
  }
}
