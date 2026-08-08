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

// Section order is not ours to set: the search page has no configured rank and
// orders sections by whichever connector answers first, so this one -- reading a
// local database while People waits on Elasticsearch -- will often land above
// People. Holding our own answer back to lose that race was tried and removed:
// it made every search wait on a delay that bought nothing but a position, and a
// slow search is worse than a section in an unexpected place.

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
