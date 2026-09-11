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
      throw new Error('Error when creating email connector');
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
      throw new Error('Error when updating email connector');
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