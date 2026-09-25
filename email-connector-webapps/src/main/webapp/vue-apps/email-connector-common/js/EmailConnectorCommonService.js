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

export function getUserEmailSetting() {
  return fetch('/email-connector/rest/user-email-setting', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting user email setting');
    }
  });
}

/**
 * Turns the CardDAV address-book sync on or off for the caller. It signs in with
 * the mailbox's own credentials, so that is the whole setting.
 *
 * @param {object} binding - {carddavEnabled}
 * @returns {Promise} resolves once stored
 */
export function updateAddressBookBinding(binding) {
  return fetch('/email-connector/rest/user-email-setting/address-book', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT',
    body: JSON.stringify(binding)
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when updating the address book binding');
    }
  });
}

/**
 * Turns the automatic address-book push on or off for the caller: whether a
 * contact they add through the contact form goes to their address book by
 * itself.
 * <p>
 * Its own endpoint, not a second field on the binding above: changing the
 * binding releases the contacts of the book being left, which a preference
 * about future saves must never trigger.
 *
 * @param {object} preference - {carddavAutoPublish}
 * @returns {Promise} resolves once stored
 */
export function updateAddressBookAutoPublish(preference) {
  return fetch('/email-connector/rest/user-email-setting/address-book/auto-publish', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT',
    body: JSON.stringify(preference)
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when updating the automatic address book publishing');
    }
  });
}

export function updateEmailPreferences(preferences) {
  return fetch('/email-connector/rest/user-email-setting/preferences', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT',
    body: JSON.stringify(preferences)
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when updating email preferences');
    }
  });
}

export function resetAndResyncMailbox() {
  return fetch('/email-connector/rest/email-box/reset', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when resetting the mailbox');
    }
  });
}

export function getAvailableEmailCategories() {
  return fetch('/email-connector/rest/email-box/categories/available', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then(resp => (resp?.ok ? resp.json() : []));
}

export function openEmailBox() {
  const quickActions = extensionRegistry.loadExtensions('QuickAction', 'Extension');
  if (quickActions?.length) {
    const emailExtension = quickActions.find(ext => ext.id === 'email');
    if (emailExtension && typeof emailExtension.click === 'function') {
      emailExtension.click();
    }
  }
}

/**
 * Pulls the caller's address book into their contacts now, rather than waiting
 * for the next scheduled run.
 *
 * @param {boolean} full - re-read everything rather than only what changed
 * @returns {Promise} resolves once the run finished
 */
export function syncAddressBook(full) {
  return fetch(`/email-connector/rest/contacts/carddav/sync?full=${!!full}`, {
    credentials: 'include',
    method: 'POST'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when syncing the address book');
    }
  });
}

/**
 * How the caller's last address-book sync went.
 *
 * @returns {Promise<object>} the sync state
 */
export function getAddressBookSyncStatus() {
  return fetch('/email-connector/rest/contacts/carddav/status', {
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when reading the address book sync status');
    }
    return resp.json();
  });
}

/**
 * The publishes the caller's address book has not taken yet — pending entries
 * waiting for the next successful sync, parked ones with why. What the
 * settings screen turns into "N contacts waiting to publish".
 *
 * @returns {Promise<object>} the queue, with an `entries` array
 */
export function getAddressBookPublishQueue() {
  return fetch('/email-connector/rest/contacts/carddav/publish-queue', {
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when reading the address book publish queue');
    }
    return resp.json();
  });
}

/**
 * The caller's email signature: the on/off switch, their own markup when they
 * wrote one, and the default the server composed from their profile.
 *
 * @returns {Promise<object>} {enabled, customHtml, defaultHtml, customLogo}
 */
export function getEmailSignature() {
  return fetch('/email-connector/rest/user-email-setting/signature', {
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when reading the email signature');
    }
    return resp.json();
  });
}

/**
 * Stores the caller's signature preference. A null (or blank) customHtml means
 * "back to the default", which then keeps following the profile.
 *
 * @param {object} signature - {enabled, customHtml}
 * @returns {Promise} resolves once stored
 */
export function saveEmailSignature(signature) {
  return fetch('/email-connector/rest/user-email-setting/signature', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT',
    body: JSON.stringify(signature)
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when saving the email signature');
    }
  });
}

/**
 * The signature image's address, versioned so replacing the image is a new URL
 * rather than a stale browser cache.
 *
 * @param {number|string} version - anything that changes when the image does
 * @returns {string} the image URL
 */
export function getSignatureImageUrl(version) {
  return `/email-connector/rest/user-email-setting/signature/image?v=${version}`;
}

/**
 * Replaces the signature image with the picture the platform's cropper
 * uploaded.
 *
 * @param {string} uploadId - the upload id the crop drawer produced
 * @returns {Promise} resolves once stored
 */
export function saveSignatureImage(uploadId) {
  return fetch(`/email-connector/rest/user-email-setting/signature/image?uploadId=${encodeURIComponent(uploadId)}`, {
    credentials: 'include',
    method: 'PUT'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when saving the signature image');
    }
  });
}

/**
 * Puts the signature image back to the company logo.
 *
 * @returns {Promise} resolves once reset
 */
export function resetSignatureImage() {
  return fetch('/email-connector/rest/user-email-setting/signature/image', {
    credentials: 'include',
    method: 'DELETE'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when resetting the signature image');
    }
  });
}

/**
 * The caller's read-receipt preferences (EXO-90435): whether the composer asks for a
 * receipt by default, how a request someone else made is answered (ASK, NEVER or
 * ALWAYS), and whether the administrator allows ALWAYS at all.
 *
 * @returns {Promise<object>} {requestByDefault, responsePolicy, alwaysAllowed}
 */
export function getReadReceiptSettings() {
  return fetch('/email-connector/rest/user-email-setting/read-receipts', {
    credentials: 'include',
    cache: 'no-store',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting the read receipt settings');
    }
  });
}

/**
 * Stores the caller's read-receipt preferences. The server refuses ALWAYS while the
 * administrator disables it, and answers the preferences as they now stand.
 *
 * @param {object} settings - {requestByDefault, responsePolicy}
 * @returns {Promise<object>} the stored preferences, alwaysAllowed included
 */
export function saveReadReceiptSettings(settings) {
  return fetch('/email-connector/rest/user-email-setting/read-receipts', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT',
    body: JSON.stringify({
      requestByDefault: !!settings?.requestByDefault,
      responsePolicy: settings?.responsePolicy || 'ASK',
    })
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when saving the read receipt settings');
    }
  });
}

/**
 * Turns a refused absence request into an Error carrying the server's message code
 * ("emailConnector.absence.*") as its message, and, for a 409, the name of the script
 * the conflict is about as `scriptName`, so the screen can say why in the user's words.
 *
 * @param {Response} resp - the refused answer
 * @param {string} fallback - the message when the answer carries no code
 * @returns {Promise<never>} rejected with the error
 */
function absenceError(resp, fallback) {
  return resp.json()
    .catch(() => ({}))
    .then(body => {
      const error = new Error(body?.message || fallback);
      error.status = resp?.status;
      error.scriptName = body?.scriptName;
      throw error;
    });
}

/**
 * The caller's automatic reply section, read live from their mail server: what the
 * engine can do, the reply the server holds, and its state (OWN, ELSEWHERE, MODIFIED,
 * INACTIVE or NONE). The browser's time zone is sent, so a reply the server stores as
 * instants (BlueMind) is answered in the user's own days. With the forward, the
 * mailbox's forward read-only (null when the deployment hides it); without it, the
 * server is not asked for it.
 *
 * @param {boolean} [withForwarding=true] - whether to read the mailbox's forward
 * @returns {Promise<object>} {capabilities, engine, vacation, vacationState, foreignScriptName, vacationDays, forwarding}
 */
export function getAbsence(withForwarding = true) {
  const params = new URLSearchParams();
  const timeZone = new Intl.DateTimeFormat().resolvedOptions().timeZone;
  if (timeZone) {
    params.set('timeZone', timeZone);
  }
  if (!withForwarding) {
    params.set('forwarding', 'false');
  }
  const query = params.toString();
  return fetch(`/email-connector/rest/user-email-setting/absence${query ? `?${query}` : ''}`, {
    credentials: 'include',
    cache: 'no-store',
    method: 'GET'
  }).then(resp => (resp?.ok ? resp.json() : absenceError(resp, 'Error when reading the automatic reply')));
}

/**
 * Writes the caller's automatic reply on their mail server.
 *
 * @param {object} vacation - {enabled, start, end, subject, text}; the browser's time zone is added
 * @param {boolean} republish - overwrite eXo's own script although it changed outside eXo
 * @returns {Promise<object>} the section after the write, capabilities not re-read
 */
export function saveVacation(vacation, republish) {
  return fetch(`/email-connector/rest/user-email-setting/absence/vacation${republish ? '?republish=true' : ''}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT',
    body: JSON.stringify({
      enabled: !!vacation?.enabled,
      start: vacation?.start || null,
      end: vacation?.end || null,
      subject: vacation?.subject || '',
      text: vacation?.text || '',
      timeZone: new Intl.DateTimeFormat().resolvedOptions().timeZone,
    })
  }).then(resp => (resp?.ok ? resp.json() : absenceError(resp, 'Error when saving the automatic reply')));
}

/**
 * Switches the caller's automatic reply off; its text stays on the server.
 *
 * @returns {Promise<void>} resolved once switched off
 */
export function disableVacation() {
  return fetch('/email-connector/rest/user-email-setting/absence/vacation', {
    credentials: 'include',
    method: 'DELETE'
  }).then(resp => (resp?.ok ? null : absenceError(resp, 'Error when switching the automatic reply off')));
}

/**
 * The dates-only summary of the caller's automatic reply, for the mailbox band.
 *
 * @returns {Promise<object>} {enabled, start, end, timeZone, source, updatedDate}
 */
export function getAbsenceStatus() {
  return fetch('/email-connector/rest/user-email-setting/absence/status', {
    credentials: 'include',
    cache: 'no-store',
    method: 'GET'
  }).then(resp => (resp?.ok ? resp.json() : absenceError(resp, 'Error when reading the automatic reply status')));
}
