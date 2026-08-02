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

import EmailSearchResult from './components/EmailSearchResult.vue';

const components = {
  'email-search-result': EmailSearchResult,
};

for (const key in components) {
  Vue.component(key, components[key]);
}

/**
 * Maps this add-on's own search response into the rows the search app renders.
 *
 * The app calls this when the module exports it, which is what lets a connector be
 * federated — answering from its own REST rather than from Elasticsearch, in
 * whatever shape that REST already had.
 *
 * The last row is not a message: it is the way out to the whole mailbox. The
 * connector deliberately searches only the locally cached mail so the global search
 * never waits on IMAP, so the user is told what was searched and offered the rest.
 *
 * @param {Object} response the /email-box/search/cached payload
 * @param {String} term what the user searched for
 * @returns {Array} the rows to render, newest first
 */
export function formatSearchResult(response, term) {
  const results = (response && response.results || []).map(result => ({
    ...result,
    // The search app keys rows by id; a UID is only unique within its folder.
    id: `${result.folder}:${result.mailRemoteId}`,
    term,
  }));
  if (!results.length) {
    return results;
  }
  results.push({
    id: 'email-search-all',
    searchAll: true,
    term,
    totalMatches: response && response.totalMatches || results.length,
  });
  return results;
}
