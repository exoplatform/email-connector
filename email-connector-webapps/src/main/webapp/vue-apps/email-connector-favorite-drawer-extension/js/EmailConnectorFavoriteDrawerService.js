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

// The calls of the platform's Favorites drawer. Kept apart from the mailbox service:
// this extension loads on every page, the mailbox bundle only in the mailbox.

/**
 * Reads a favorited email by its technical id, the id the favorites store keys it by.
 *
 * @param {String} id the email's technical id
 * @returns {Promise<Object>} the email
 */
export function getFavoriteEmail(id) {
  return fetch(`/email-connector/rest/email-box/favorites/${id}`, {
    method: 'GET',
    credentials: 'include',
  }).then(response => {
    if (!response?.ok) {
      throw new Error('Favorited email cannot be read');
    }
    return response.json();
  });
}

/**
 * Removes a favorited email: the server clears the star of every copy of the message
 * the favorite stands for, each in its folder.
 *
 * @param {String} id the email's technical id
 * @returns {Promise<Object>} {failedUpdates}, how many copies could not be unstarred
 */
export function removeFavoriteEmail(id) {
  return fetch(`/email-connector/rest/email-box/favorites/${id}`, {
    method: 'DELETE',
    credentials: 'include',
  }).then(response => {
    if (!response?.ok) {
      throw new Error('Favorited email cannot be unstarred');
    }
    return response.json();
  });
}
