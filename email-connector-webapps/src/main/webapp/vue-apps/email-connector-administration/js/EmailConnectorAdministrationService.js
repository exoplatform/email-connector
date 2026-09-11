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
 
/**
 * Turns a refused response into an Error carrying what the server said.
 *
 * The provider configuration is validated server-side and refused with a message
 * code - a missing required field, a value outside a field's options. Thrown as a
 * bare sentence that code never reaches the screen, and the administrator is told
 * "error" about a form they can in fact correct.
 *
 * @param {Response} resp the refused response
 * @param {string} fallback message to use when the body carries nothing
 * @returns {Promise} a promise rejecting with the Error to throw
 */
function refusal(resp, fallback) {
  return resp.text().then(body => {
    const error = new Error(body || fallback);
    error.messageCode = body || null;
    throw error;
  });
}

export function activateEmailFeature(emailFeatureActive) {
  return fetch(`/email-connector/rest/connectors/feature/activation?active=${emailFeatureActive}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PATCH'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when activating email feature');
    }
  });
}

export function createEmailConnector(emailConnector) {
  return fetch('/email-connector/rest/connectors', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    body: JSON.stringify(emailConnector),
    method: 'POST'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      return refusal(resp, 'Error when creating email connector');
    }
  });
}

export function updateEmailConnector(emailConnector) {
  return fetch('/email-connector/rest/connectors', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    body: JSON.stringify(emailConnector),
    method: 'PUT'
  }).then((resp) => {
    if (!resp?.ok) {
      return refusal(resp, 'Error when updating email connector');
    }
  });
}

export function activateEmailConnector(emailConnectorId, emailConnectorActive) {
  return fetch(`/email-connector/rest/connectors/${emailConnectorId}?active=${emailConnectorActive}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PATCH'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when activating email connector');
    }
  });
}

export function getEmailConnectors() {
  return fetch('/email-connector/rest/connectors', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting email connectors');
    }
  });
}

export function getEmailBoxCacheSize() {
  return fetch('/email-connector/rest/connectors/cache-size', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting the mailbox cache size');
    }
  });
}

export function updateEmailBoxCacheSize(size) {
  return fetch(`/email-connector/rest/connectors/cache-size?size=${size}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when updating the mailbox cache size');
    }
  });
}

export function getEmailBoxSyncPeriod() {
  return fetch('/email-connector/rest/connectors/sync-period', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting the mailbox sync period');
    }
  });
}

export function updateEmailBoxSyncPeriod(minutes) {
  return fetch(`/email-connector/rest/connectors/sync-period?minutes=${minutes}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when updating the mailbox sync period');
    }
  });
}

/**
 * The administration-wide sync period of the inactive mailboxes, in minutes.
 *
 * @returns {Promise<Number>} the period, never below the active one (the server
 *   clamps it on read)
 */
export function getEmailBoxInactiveSyncPeriod() {
  return fetch('/email-connector/rest/connectors/inactive-sync-period', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting the inactive mailbox sync period');
    }
  });
}

/**
 * Saves the administration-wide sync period of the inactive mailboxes.
 *
 * @param {Number} minutes the period, in minutes (at least the active period,
 *   at most 1440 — the server answers 400 otherwise)
 * @returns {Promise<void>} rejected when the server refuses the value
 */
export function saveEmailBoxInactiveSyncPeriod(minutes) {
  return fetch(`/email-connector/rest/connectors/inactive-sync-period?minutes=${minutes}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when updating the inactive mailbox sync period');
    }
  });
}

/**
 * The administration-wide activity threshold: how many days without opening
 * the mailbox make its owner inactive.
 *
 * @returns {Promise<Number>} the threshold, in days
 */
export function getEmailBoxActivityThresholdDays() {
  return fetch('/email-connector/rest/connectors/activity-threshold', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting the mailbox activity threshold');
    }
  });
}

/**
 * Saves the administration-wide activity threshold.
 *
 * @param {Number} days the threshold, in days (1 to 365 — the server answers
 *   400 otherwise)
 * @returns {Promise<void>} rejected when the server refuses the value
 */
export function saveEmailBoxActivityThresholdDays(days) {
  return fetch(`/email-connector/rest/connectors/activity-threshold?days=${days}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when updating the mailbox activity threshold');
    }
  });
}

/**
 * The size of the mailbox sync executor: how many mailboxes each server node
 * synchronizes at once.
 *
 * @returns {Promise<Number>} the executor size, in threads
 */
export function getEmailSyncThreads() {
  return fetch('/email-connector/rest/connectors/sync-threads', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting the mailbox sync threads');
    }
  });
}

/**
 * Saves the size of the mailbox sync executor.
 *
 * @param {Number} threads the executor size (1 to 64 — the server answers 400
 *   otherwise)
 * @returns {Promise<void>} rejected when the server refuses the value
 */
export function saveEmailSyncThreads(threads) {
  return fetch(`/email-connector/rest/connectors/sync-threads?threads=${threads}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when updating the mailbox sync threads');
    }
  });
}

/**
 * A snapshot of the mailbox sync dispatcher, for the drawer's status line.
 *
 * @returns {Promise<Object>} {node, running, queued, threads, claimed,
 *   dueBacklog, oldestDueMinutes, connectedMailboxes}
 */
export function getEmailSyncStatus() {
  return fetch('/email-connector/rest/connectors/sync-status', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting the mailbox sync status');
    }
  });
}

export function getTrashSyncEnabled() {
  return fetch('/email-connector/rest/connectors/trash-sync', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting the Trash folder sync switch');
    }
  });
}

export function updateTrashSyncEnabled(enabled) {
  return fetch(`/email-connector/rest/connectors/trash-sync?enabled=${enabled}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PATCH'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when updating the Trash folder sync switch');
    }
  });
}

export function getJunkSyncEnabled() {
  return fetch('/email-connector/rest/connectors/junk-sync', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting the Junk folder sync switch');
    }
  });
}

export function updateJunkSyncEnabled(enabled) {
  return fetch(`/email-connector/rest/connectors/junk-sync?enabled=${enabled}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PATCH'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when updating the Junk folder sync switch');
    }
  });
}

export function getServerDraftsEnabled() {
  return fetch('/email-connector/rest/connectors/drafts-server', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting the server-side drafts switch');
    }
  });
}

export function updateServerDraftsEnabled(enabled) {
  return fetch(`/email-connector/rest/connectors/drafts-server?enabled=${enabled}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PATCH'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when updating the server-side drafts switch');
    }
  });
}

export function getCustomFoldersEnabled() {
  return fetch('/email-connector/rest/connectors/custom-folders-sync', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting the custom folders switch');
    }
  });
}

export function updateCustomFoldersEnabled(enabled) {
  return fetch(`/email-connector/rest/connectors/custom-folders-sync?enabled=${enabled}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PATCH'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when updating the custom folders switch');
    }
  });
}

export function deleteEmailConnector(emailConnectorId) {
  return fetch(`/email-connector/rest/connectors/${emailConnectorId}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'DELETE'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when deleting email connector');
    }
  });
}

/**
 * Reads back the provider configuration stored for a connector.
 *
 * Secret values are never in the answer: the endpoint omits them, so a secret
 * field opens empty and an unrelated save leaves the stored one untouched.
 *
 * @param {number} emailConnectorId technical id of the connector
 * @returns {Promise} the stored values, keyed by descriptor field
 */
export function getProviderConfig(emailConnectorId) {
  return fetch(`/email-connector/rest/connectors/${emailConnectorId}/provider-config`, {
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when retrieving the provider configuration');
    }
    return resp.json();
  });
}
