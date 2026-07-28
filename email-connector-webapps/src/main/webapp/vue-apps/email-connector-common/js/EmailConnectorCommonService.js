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