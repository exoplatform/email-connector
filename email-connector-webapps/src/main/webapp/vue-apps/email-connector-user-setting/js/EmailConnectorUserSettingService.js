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

/**
 * Whether each declared provider asks the user for anything, keyed by provider
 * name. A provider answering false connects in one click: the platform holds
 * what it takes, and there is no form to show.
 *
 * @returns {Promise<Object>} provider name to boolean
 */
export function getConnectionRequirements() {
  return fetch('/email-connector/rest/connectors/connection-requirements', {
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting connection requirements');
    }
  });
}

/**
 * Connects to a connector whose provider asks the user for nothing. The server
 * opens the mailbox with the service account's own material and records the
 * connection only if that worked, so a resolved promise means tested — the same
 * promise the typed form makes.
 *
 * @param {Number} emailConnectorId the connector to connect to
 * @returns {Promise} resolves once connected
 */
export function connectThroughProvider(emailConnectorId) {
  return fetch(`/email-connector/rest/user-email-setting/connect?emailConnectorId=${emailConnectorId}`, {
    credentials: 'include',
    method: 'POST'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when connecting through the configured provider');
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
 * How many mails the user has scheduled to be sent (EXO-90434): what disconnecting
 * cancels, and what the disconnect confirmation warns about. An unreadable count is
 * none, as the contacts count is: the disconnect is never held hostage by it.
 *
 * @returns {Promise<Number>} the count
 */
export function getScheduledEmailsCount() {
  return fetch('/email-connector/rest/email-box/scheduled/count', {
    credentials: 'include',
    cache: 'no-store',
  }).then(resp => (resp?.ok ? resp.json() : 0))
    .then(count => Number(count) || 0)
    .catch(() => 0);
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

/**
 * Who has access to the caller's own mailbox, read live from the mail server
 * (EXO-90503). The answer carries the server's capabilities beside the grantees, so
 * an unsupported server can be said to be unsupported rather than shown an empty
 * list; entries granted outside eXo are in it too, and are the server's, not eXo's.
 *
 * @returns {Promise<Object>} {capabilities, ownerMailbox, grantees}
 */
export function getGrantedDelegations() {
  return fetch('/email-connector/rest/user-email-setting/delegations/granted', {
    credentials: 'include',
    cache: 'no-store',
    method: 'GET'
  }).then(resp => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.json()
      .catch(() => ({}))
      .then(body => {
        throw new Error(body?.message || 'Error when reading who has access to your mailbox');
      });
  });
}

/**
 * The mailboxes shared with the caller, in every state. With discovery the caller's
 * own session walks their mail server's shared namespace first, so a share granted in
 * the mail server's own interface is offered rather than missed — it costs a
 * connection, so the settings row reads without it and only the drawer asks for it.
 *
 * @param {Boolean} discover whether to walk the mail server as well
 * @returns {Promise<Array>} the delegation rows
 */
export function getReceivedDelegations(discover) {
  return fetch(`/email-connector/rest/user-email-setting/delegations/received?discover=${!!discover}`, {
    credentials: 'include',
    cache: 'no-store',
    method: 'GET'
  }).then(resp => {
    if (!resp?.ok) {
      throw new Error('Error when reading the mailboxes shared with you');
    }
    return resp.json();
  });
}

/**
 * Shares the caller's own mailbox with another eXo user. The access is written on the
 * mail server now, not when the invitation is answered: only the caller can take it
 * away afterwards. A refusal carries the server's message code as the error message
 * ("emailConnector.delegation.*"), so the screen can say why in the user's words.
 *
 * @param {String} granteeUsername the eXo username to share with
 * @param {String} preset READER or EDITOR
 * @param {Object} folderAccess optionally, the owner's choice for Sent, Archive, Trash
 *        and Spam, by role -- {TRASH: 'NONE'} (EXO-90556); a role absent follows the preset
 * @returns {Promise<Object>} the delegation as created
 */
export function inviteDelegation(granteeUsername, preset, folderAccess) {
  const body = {granteeUsername, preset};
  if (folderAccess && Object.keys(folderAccess).length) {
    body.folderAccess = folderAccess;
  }
  return fetch('/email-connector/rest/user-email-setting/delegations', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST',
    body: JSON.stringify(body)
  }).then(resp => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.json()
      .catch(() => ({}))
      .then(body => {
        throw new Error(body?.message || 'Error when sharing your mailbox');
      });
  });
}

/**
 * The caller's own folders that can be shared one by one, for the invitation's folder
 * choice (EXO-90556): INBOX first, then Sent, Archive, Trash and Spam, then the caller's
 * other folders as a tree; never Drafts. No access is read.
 *
 * @returns {Promise<Object>} {folders, truncated}; rejects with the server's message code
 */
export function getShareableFolders() {
  return fetch('/email-connector/rest/user-email-setting/delegations/folders', {
    credentials: 'include',
    cache: 'no-store',
    method: 'GET'
  }).then(resp => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.json().catch(() => ({})).then(body => {
      throw new Error(body?.message || 'Error when reading your folders');
    });
  });
}

/**
 * The caller's folders with the access one person holds in each, as the mail server
 * says it now (EXO-90556).
 *
 * @param {Number} id the delegation id
 * @returns {Promise<Object>} {folders, truncated}; rejects with the server's message code
 */
export function getDelegationFolders(id) {
  return fetch(`/email-connector/rest/user-email-setting/delegations/${id}/folders`, {
    credentials: 'include',
    cache: 'no-store',
    method: 'GET'
  }).then(resp => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.json().catch(() => ({})).then(body => {
      throw new Error(body?.message || 'Error when reading what you share');
    });
  });
}

/**
 * Sets the access one person holds in some of the caller's folders, on the mail server
 * (EXO-90556). Each folder is its own write: the answer says what became of each.
 *
 * @param {Number} id the delegation id
 * @param {Array} folders the changes, [{folder, access}] with access READER, EDITOR or NONE
 * @returns {Promise<Object>} {delegation, results}; rejects with the server's message code
 */
export function setDelegationFolders(id, folders) {
  return fetch(`/email-connector/rest/user-email-setting/delegations/${id}/folders`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT',
    body: JSON.stringify({folders}),
  }).then(resp => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.json().catch(() => ({})).then(body => {
      throw new Error(body?.message || 'Error when changing what you share');
    });
  });
}

/**
 * Removes a grantee's access to the caller's own mailbox, on the mail server. The only
 * call in this file that takes access away: declining and leaving do not.
 *
 * @param {Number} id the delegation id
 * @returns {Promise<void>} resolved once the access is gone
 */
export function revokeDelegation(id) {
  return fetch(`/email-connector/rest/user-email-setting/delegations/${id}`, {
    credentials: 'include',
    method: 'DELETE'
  }).then(resp => {
    if (resp?.ok) {
      return;
    }
    return resp.json()
      .catch(() => ({}))
      .then(body => {
        throw new Error(body?.message || 'Error when removing access to your mailbox');
      });
  });
}

/**
 * Shares with a grantee the owner's Sent, Archive, Trash and Spam, beside the Inbox an
 * older share covers (EXO-90548): the owner's "Extend access".
 *
 * @param {Number} id the delegation id
 * @returns {Promise<Object>} the delegation as it now stands; rejects with the server's
 *          message code
 */
export function extendDelegation(id) {
  return fetch(`/email-connector/rest/user-email-setting/delegations/${id}/extend`, {
    credentials: 'include',
    method: 'POST',
  }).then(resp => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.json().catch(() => ({})).then(body => {
      throw new Error(body?.message || 'Error when extending the access to your mailbox');
    });
  });
}

/**
 * Changes the access a grantee holds on the caller's own mailbox to another preset --
 * written on the mail server, replacing what the grantee held there. A refusal carries
 * the server's message code as the error message.
 *
 * @param {Number} id the delegation id
 * @param {String} preset READER or EDITOR
 * @returns {Promise<Object>} the delegation as it now stands
 */
export function changeDelegationPreset(id, preset) {
  return fetch(`/email-connector/rest/user-email-setting/delegations/${id}/preset`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT',
    body: JSON.stringify({preset}),
  }).then(resp => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.json()
      .catch(() => ({}))
      .then(body => {
        throw new Error(body?.message || 'Error when changing the access to your mailbox');
      });
  });
}

/**
 * Sets, changes or withdraws the caller's consent to one grantee writing mail in the
 * caller's name (EXO-90582). Recorded on the share; nothing is sent. A refusal carries
 * the server's message code as the error message.
 *
 * @param {Number} id the delegation id
 * @param {String} sendMode NONE, ON_BEHALF or AS
 * @returns {Promise<Object>} the delegation as it now stands
 */
export function setDelegationSendMode(id, sendMode) {
  return fetch(`/email-connector/rest/user-email-setting/delegations/${id}/send-mode`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT',
    body: JSON.stringify({sendMode}),
  }).then(resp => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.json()
      .catch(() => ({}))
      .then(body => {
        throw new Error(body?.message || 'Error when changing who may write mail in your name');
      });
  });
}

/**
 * Answers a share: accept it, decline it, or leave one already accepted. Three verbs
 * on one path because the server decides what each means for the row — and none of
 * them touches the access itself, which stays the owner's to remove.
 *
 * @param {Number} id the delegation id
 * @param {String} answer accept, decline or leave
 * @returns {Promise<Object>} the delegation as it now stands
 */
export function answerDelegation(id, answer) {
  return fetch(`/email-connector/rest/user-email-setting/delegations/${id}/${answer}`, {
    credentials: 'include',
    method: 'PUT'
  }).then(resp => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.json()
      .catch(() => ({}))
      .then(body => {
        throw new Error(body?.message || 'Error when answering a shared mailbox');
      });
  });
}

/**
 * The caller's own toggles on one shared mailbox. A field left out stays as it is.
 *
 * @param {Number} id the delegation id
 * @param {Object} preferences {badgeIncluded, notifyNewMail, searchIncluded}
 * @returns {Promise<Object>} the delegation as it now stands
 */
export function updateDelegationPreferences(id, preferences) {
  return fetch(`/email-connector/rest/user-email-setting/delegations/${id}/preferences`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT',
    body: JSON.stringify(preferences)
  }).then(resp => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.json()
      .catch(() => ({}))
      .then(body => {
        throw new Error(body?.message || 'Error when saving your preferences on a shared mailbox');
      });
  });
}

/**
 * Turns a refused request of the filters into an Error carrying the server's message
 * code as its message and, for a 409, the name of the script the conflict is about as
 * `scriptName`, with the answer's status as `status`.
 *
 * @param {Response} resp - the refused answer
 * @param {string} fallback - the message when the answer carries no code
 * @returns {Promise<never>} rejected with the error
 */
function filtersError(resp, fallback) {
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
 * The server group of the user's filters, read live from their mail server: what the
 * engine can do, the rules eXo manages there in the order the server applies them, their
 * state (OWN, INACTIVE, MODIFIED or NONE), another client's script the server also runs,
 * and whether the user already agreed that eXo manages rules on their server.
 *
 * @returns {Promise<object>} {capabilities, engine, rules, state, foreignScriptName, consented}
 */
export function getServerFilters() {
  return fetch('/email-connector/rest/email-box/filters/server', {
    credentials: 'include',
    cache: 'no-store',
    method: 'GET'
  }).then(resp => (resp?.ok ? resp.json() : filtersError(resp, 'Error when reading the filters')));
}

/**
 * Creates, or replaces, one filter the mail server runs at delivery.
 *
 * @param {object} rule - {name, enabled, matchAll, conditions, actions, stop}; a move names its folder by key
 * @param {string} [ref] - the filter to replace; none to create one
 * @param {object} [options] - {consent, republish}
 * @returns {Promise<object>} the group after the write, capabilities not re-read
 */
export function saveServerFilter(rule, ref, options) {
  const params = new URLSearchParams();
  if (options?.consent) {
    params.set('consent', 'true');
  }
  if (options?.republish) {
    params.set('republish', 'true');
  }
  const query = params.toString();
  const path = ref ? `/server/${encodeURIComponent(ref)}` : '/server';
  return fetch(`/email-connector/rest/email-box/filters${path}${query ? `?${query}` : ''}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: ref ? 'PUT' : 'POST',
    body: JSON.stringify(rule)
  }).then(resp => (resp?.ok ? resp.json() : filtersError(resp, 'Error when saving the filter')));
}

/**
 * Deletes one filter the mail server runs at delivery.
 *
 * @param {string} ref - the filter
 * @param {boolean} [republish] - overwrite eXo's script although it changed outside eXo
 * @returns {Promise<object>} the group after the write, capabilities not re-read
 */
export function deleteServerFilter(ref, republish) {
  return fetch(`/email-connector/rest/email-box/filters/server/${encodeURIComponent(ref)}${republish ? '?republish=true' : ''}`, {
    credentials: 'include',
    method: 'DELETE'
  }).then(resp => (resp?.ok ? resp.json() : filtersError(resp, 'Error when deleting the filter')));
}

/**
 * Writes the filters eXo manages again and makes the server run them: Re-activate, or,
 * with republish, Re-publish over an edit made outside eXo.
 *
 * @param {boolean} [republish] - overwrite eXo's script although it changed outside eXo
 * @returns {Promise<object>} the group after the write, capabilities not re-read
 */
export function publishServerFilters(republish) {
  return fetch(`/email-connector/rest/email-box/filters/server/publish${republish ? '?republish=true' : ''}`, {
    credentials: 'include',
    method: 'POST'
  }).then(resp => (resp?.ok ? resp.json() : filtersError(resp, 'Error when publishing the filters')));
}

/**
 * The query string of the eXo group's writes: consent and republish when set.
 *
 * @param {object} [options] - {consent, republish, withAgent, action, limit}
 * @returns {string} the query, with its leading "?", or empty
 */
function filterQuery(options) {
  const params = new URLSearchParams();
  Object.entries(options || {}).forEach(([key, value]) => {
    if (typeof value !== 'undefined' && value !== null && value !== false && value !== '') {
      params.set(key, String(value));
    }
  });
  const query = params.toString();
  return query ? `?${query}` : '';
}

/**
 * Sends a request of the eXo group and reads its answer; a refusal becomes the Error
 * filtersError makes of it.
 *
 * @param {string} path - the path under /email-box/filters, from its first "/"
 * @param {string} method - the HTTP method
 * @param {object} body - the JSON body, null for none
 * @param {string} fallback - the message when the refusal carries no code
 * @returns {Promise<object>} the answer, or null when it has no body
 */
function filterRequest(path, method, body, fallback) {
  return fetch(`/email-connector/rest/email-box/filters${path}`, {
    headers: body === null ? {} : { 'Content-Type': 'application/json' },
    credentials: 'include',
    cache: 'no-store',
    method,
    ...(body === null ? {} : { body: JSON.stringify(body) }),
  }).then(resp => {
    if (!resp?.ok) {
      return filtersError(resp, fallback);
    }
    return resp.status === 204 ? null : resp.json().catch(() => null);
  });
}

/**
 * The eXo group of the user's filters: the rules eXo runs after each sync of their own
 * inbox, in the order they run. Never reads the mail server.
 *
 * @returns {Promise<object[]>} the rules
 */
export function getExoFilters() {
  return filterRequest('', 'GET', null, 'Error when reading the filters');
}

/**
 * Creates, or replaces, one rule eXo runs after each sync. A rule of kind HOP also runs
 * at delivery: its server half is written first, and nothing is stored when the server
 * refuses (409 {message, scriptName}, 502).
 *
 * @param {object} filter - {name, enabled, kind, matchAll, conditions, actions, stopProcessing}
 * @param {number} [id] - the rule to replace; none to create one
 * @param {object} [options] - {consent, republish}
 * @returns {Promise<object>} the rule as stored
 */
export function saveExoFilter(filter, id, options) {
  const query = filterQuery({ consent: options?.consent, republish: options?.republish });
  return filterRequest(id ? `/${id}${query}` : query, id ? 'PUT' : 'POST', filter, 'Error when saving the filter');
}

/**
 * Deletes one rule eXo runs after each sync, and its server half first when it has one.
 *
 * @param {number} id - the rule
 * @param {boolean} [republish] - overwrite eXo's script although it changed outside eXo
 * @returns {Promise<void>} resolved once deleted
 */
export function deleteExoFilter(id, republish) {
  return filterRequest(`/${id}${filterQuery({ republish })}`, 'DELETE', null, 'Error when deleting the filter');
}

/**
 * Orders the rules eXo runs after each sync.
 *
 * @param {number[]} ids - every rule's id, once, in the new order
 * @returns {Promise<object[]>} the rules, in their new order
 */
export function reorderExoFilters(ids) {
  return filterRequest('/order', 'PUT', ids, 'Error when ordering the filters');
}

/**
 * What a rule would match among the mail eXo keeps of the user's inbox.
 *
 * @param {object} draft - {kind (EXO, HOP or SERVER), matchAll, conditions}
 * @returns {Promise<object>} {total, scanned, sample, notPreviewable, approximate}
 */
export function previewFilter(draft) {
  return filterRequest('/preview', 'POST', draft, 'Error when previewing the filter');
}

/**
 * Runs a rule once over the mail already in the user's inbox.
 *
 * @param {number} id - the rule
 * @param {boolean} [withAgent] - queue its assistant on the newest matches too
 * @returns {Promise<object>} {scanned, matched, alreadyHandled, queued, notApplicable}
 */
export function applyExoFilter(id, withAgent) {
  return filterRequest(`/${id}/apply${filterQuery({ withAgent })}`, 'POST', null, 'Error when applying the filter');
}

/**
 * Publishes a rule's server half again, after it was removed or changed on the server.
 *
 * @param {number} id - the rule
 * @param {object} [options] - {consent, republish}
 * @returns {Promise<object>} the rule
 */
export function republishExoFilter(id, options) {
  const query = filterQuery({ consent: options?.consent, republish: options?.republish });
  return filterRequest(`/${id}/republish${query}`, 'POST', null, 'Error when publishing the filter');
}

/**
 * What a rule did lately.
 *
 * @param {number} id - the rule
 * @param {number} [limit] - how many, at most 100
 * @returns {Promise<object[]>} the matches, newest first
 */
export function getExoFilterLog(id, limit) {
  return filterRequest(`/${id}/log${filterQuery({ limit })}`, 'GET', null, 'Error when reading the log');
}

/**
 * What the user's rules did to one of their mails: its Automations panel.
 *
 * @param {number} emailId - the cached mail's id
 * @returns {Promise<object[]>} the matches, newest first
 */
export function getMailAutomations(emailId) {
  return filterRequest(`/mail/${emailId}`, 'GET', null, 'Error when reading the automations');
}

/**
 * Undoes what a rule did to a mail: one action, or every one.
 *
 * @param {number} matchId - the match
 * @param {string} [action] - the action type; every one when absent
 * @returns {Promise<object>} the match after the undo
 */
export function undoAutomation(matchId, action) {
  return filterRequest(`/matches/${matchId}/undo${filterQuery({ action })}`, 'POST', null, 'Error when undoing');
}

/**
 * Runs a rule's assistant again on a mail.
 *
 * @param {number} matchId - the match
 * @returns {Promise<object>} the match, queued
 */
export function retryAutomation(matchId) {
  return filterRequest(`/matches/${matchId}/retry`, 'POST', null, 'Error when running the assistant again');
}
