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

export function getActiveEmailConnectors() {
  return fetch('/email-connector/rest/emailConnector/active', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting active email connectors');
    }
  });
}

export function setUserEmailSetting(userEmailSetting, broadcast) {
  return fetch(`/email-connector/rest/emailConnector/userEmailSetting/${broadcast}`, {
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
  return fetch('/email-connector/rest/emailConnector/userEmailSetting', {
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