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
import EmailSearchRow from './components/EmailSearchRow.vue';

const components = {
  'email-search-result': EmailSearchResult,
  'email-search-row': EmailSearchRow,
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
 * The last row is not a message: it carries the search on to the mail server itself.
 * The connector answers from the locally held mail so that no search in the platform
 * waits on IMAP, and that row then fetches the rest once the page is drawn, appending
 * whatever the cache could not know about. The user sees one list filling in.
 *
 * It is given the ids already on screen, because the newest hits the server returns
 * are usually ones the cache already had, and the same mail twice in one section
 * reads as a bug.
 *
 * @param {Object} response the /email-box/search/cached payload
 * @param {String} term what the user searched for
 * @returns {Array} the rows to render, newest first
 */
export function formatSearchResult(response, term) {
  const results = (response?.results || []).map(result => ({
    ...result,
    // The search app keys rows by id; a UID is only unique within its folder.
    id: `${result.folder}:${result.mailRemoteId}`,
    // Its Favorites filter is applied a second time in the browser, over every
    // connector's rows: a row survives it only if it says `favorite`. Answering a
    // favorites-narrowed request correctly is not enough — without this the whole
    // section was thrown away after arriving, and the page read "No results found"
    // while the server had just returned two.
    favorite: !!result.starred,
    term,
  }));
  results.push({
    id: 'email-search-continuation',
    continuation: true,
    term,
    // The page says whether the Favorites filter was on, so the continuation asks the
    // mail server the same narrowed question rather than a wider one.
    favoritesOnly: response?.favoritesOnly || false,
    // The browser-side filter would drop this row too, and with it the rest of the
    // mailbox: what it carries is favorites when the filter is on.
    favorite: true,
    shownIds: results.map(result => result.id),
  });
  return results;
}
