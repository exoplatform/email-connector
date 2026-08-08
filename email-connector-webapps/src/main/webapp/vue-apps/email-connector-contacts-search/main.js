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

import EmailContactSearchCard from './components/EmailContactSearchCard.vue';

const components = {
  'email-contact-search-card': EmailContactSearchCard,
};

for (const key in components) {
  Vue.component(key, components[key]);
}

// How long this connector holds its answer back, at most. The search page
// orders sections by which connector ANSWERS first (each arriving row takes the
// next global index, and sections sort by their first row's index — there is no
// configured rank), and this store answers from a local database while People
// answers from Elasticsearch: left alone, "My contacts" would usually land
// ABOVE People. The design wants it below — when both match, the colleague is
// usually who was meant — so the fetch starts immediately but the answer is
// held to this floor, long enough for People's round-trip in the common case.
// Sections stream in independently, so nothing else waits on this.
const RANK_BELOW_PEOPLE_FLOOR_MS = 600;

/**
 * Fetches this connector's results for the search page, which prefers this
 * export over its own fetch. The request goes out right away; only the
 * resolution is floored, per the ranking note above.
 *
 * @param {string} uri the connector uri, placeholders already substituted
 * @param {Object} options the fetch options, including the page's abort signal
 * @returns {Promise<Response>} the REST response, no earlier than the floor
 */
export function fetchSearchResult(uri, options) {
  const floor = new Promise(resolve => window.setTimeout(resolve, RANK_BELOW_PEOPLE_FLOOR_MS));
  return Promise.all([fetch(uri, options), floor]).then(([response]) => response);
}

/**
 * Maps the /contacts/search answer into the rows the search page renders. The
 * REST already answers display-ready contacts (one object per person, platform
 * users excluded server-side), so each row is the contact itself plus what the
 * page's own machinery needs: the term for highlighting, and the `favorite`
 * flag its browser-side Favorites re-filter keys on — without it a correctly
 * favorites-narrowed answer would be thrown away after arriving, exactly the
 * trap the email connector documents.
 *
 * @param {Array} response the /contacts/search payload, a list of contacts
 * @param {string} term what the user searched for
 * @returns {Array} the rows to render
 */
export function formatSearchResult(response, term) {
  return (response || []).map(contact => ({
    ...contact,
    favorite: !!contact.favorite,
    term,
  }));
}
