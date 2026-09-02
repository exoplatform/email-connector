/*
 * Copyright (C) 2025 eXo Platform SAS.
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

export function getUserEmailConnectors() {
  return fetch('/email-connector/rest/user-email-setting/connectors', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting user email connectors');
    }
  });
}

export function setUserEmailSetting(userEmailSetting, broadcast) {
  return fetch(`/email-connector/rest/user-email-setting?broadcast=${broadcast}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    body: JSON.stringify(userEmailSetting),
    method: 'PUT'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when setting user email setting');
    }
  });
}

export function deleteUserEmailSetting() {
  return fetch('/email-connector/rest/user-email-setting', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'DELETE'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when deleting user email setting');
    }
  });
}
/**
 * How many contacts this user would be deciding about.
 * <p>
 * Asked before the switch question is shown: on a first connection, or an
 * empty store, there is nothing to lose and the question would be noise.
 *
 * @returns {Promise<Number>} the stored contact count, 0 when unreadable
 */
export function getContactsCount() {
  return fetch('/email-connector/rest/contacts?offset=0&limit=1', {
    credentials: 'include'
  }).then(resp => {
    return resp?.ok ? resp.json() : null;
  }).then(body => body?.size || 0)
    .catch(() => 0);
}

/**
 * Downloads the whole contact store as a .vcf backup and, only once those
 * bytes have arrived, empties it.
 * <p>
 * The order is the point. The request is read to completion in the browser
 * BEFORE the file is handed to the user and before this promise resolves, so a
 * connection that dies mid-download rejects here and the server -- which
 * deletes nothing until its last byte is flushed -- has kept the store intact.
 * The caller only moves the binding after this resolves, which is what makes
 * "backup first, wipe second" true end to end rather than merely intended.
 *
 * @returns {Promise<void>} resolved once the backup is in the user's hands
 */
export function downloadContactsBackupThenStartFresh() {
  return fetch('/email-connector/rest/contacts/export-then-start-fresh', {
    credentials: 'include',
    method: 'POST'
  }).then(resp => {
    if (!resp?.ok) {
      throw new Error('Error when exporting the contacts');
    }
    return resp.blob();
  }).then(blob => {
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'contacts-backup.vcf';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
  });
}

/**
 * The user's mail folders: the built-ins their mailbox has and every folder of their
 * own, each with its mirror opt-in, plus the cap and the window the settings screen
 * states beside them. With `refresh`, the mailbox's folder list is walked first.
 *
 * @param {Boolean} refresh whether to walk the mailbox before answering
 * @returns {Promise<Object>} {folders, maxCustomFolders, enabledCustomFolders, windowSize}
 */
export function getMailFolders(refresh) {
  return fetch(`/email-connector/rest/email-box/folders${refresh ? '?refresh=true' : ''}`, {
    credentials: 'include',
    cache: 'no-store',
    method: 'GET'
  }).then(resp => {
    if (!resp?.ok) {
      throw new Error('Error when getting the mail folders');
    }
    return resp.json();
  });
}

/**
 * Mirrors, or stops mirroring, one of the user's own folders. A refusal carries the
 * server's message code as the error message ("emailConnector.folder.tooMany" at the
 * cap), so the screen can say why in the user's words.
 *
 * @param {Number} id the folder's registry id
 * @param {Boolean} enabled whether it is mirrored
 * @returns {Promise<Object>} the folder as it now stands
 */
export function setMailFolderMirror(id, enabled) {
  return fetch(`/email-connector/rest/email-box/folders/${id}?sync=${enabled}`, {
    credentials: 'include',
    method: 'PATCH'
  }).then(resp => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.json()
      .catch(() => ({}))
      .then(body => {
        throw new Error(body?.message || 'Error when updating the mail folder');
      });
  });
}

/**
 * Creates one of the user's own folders on the mail server -- a visible, permanent
 * write to the account's real mailbox, done only on this explicit call. A refusal
 * carries the server's message code as the error message
 * ("emailConnector.folder.name.duplicate", ".nested", ".reserved", ".blank",
 * ".tooLong" or ".createFailed"), so the screen can say why in the user's words.
 *
 * @param {String} name the folder name, as typed
 * @returns {Promise<Object>} the folder as registered
 */
export function createMailFolder(name) {
  return fetch(`/email-connector/rest/email-box/folders?name=${encodeURIComponent(name)}`, {
    credentials: 'include',
    method: 'POST'
  }).then(resp => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.json()
      .catch(() => ({}))
      .then(body => {
        throw new Error(body?.message || 'Error when creating the mail folder');
      });
  });
}

/**
 * Renames one of the user's own folders, on the mail server and in the registry. Only
 * the folder's own name changes, never its parent. A refusal carries the server's
 * message code the same way {@link createMailFolder} does.
 *
 * @param {Number} id the folder's registry id
 * @param {String} name the new name, as typed
 * @returns {Promise<Object>} the folder as it now stands
 */
export function renameMailFolder(id, name) {
  return fetch(`/email-connector/rest/email-box/folders/${id}/name?name=${encodeURIComponent(name)}`, {
    credentials: 'include',
    method: 'PATCH'
  }).then(resp => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.json()
      .catch(() => ({}))
      .then(body => {
        throw new Error(body?.message || 'Error when renaming the mail folder');
      });
  });
}

/**
 * Deletes one of the user's own folders, on the mail server and in the registry --
 * permanently, and refused by the server while the folder still holds mail
 * ("emailConnector.folder.notEmpty").
 *
 * @param {Number} id the folder's registry id
 * @returns {Promise<void>} resolved once the folder and its mirror are gone
 */
export function deleteMailFolder(id) {
  return fetch(`/email-connector/rest/email-box/folders/${id}`, {
    credentials: 'include',
    method: 'DELETE'
  }).then(resp => {
    if (resp?.ok) {
      return;
    }
    return resp.json()
      .catch(() => ({}))
      .then(body => {
        throw new Error(body?.message || 'Error when deleting the mail folder');
      });
  });
}
