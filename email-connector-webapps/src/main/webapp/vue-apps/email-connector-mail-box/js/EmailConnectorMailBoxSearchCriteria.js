/*
Copyright (C) 2026 eXo Platform SAS.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

// The mailbox's advanced search (EXO-90838): its criteria, and how they travel in the
// page's address, so a search can be reloaded or shared. The address carries them as
// query parameters beside openEmailBox=true, the parameter that opens the mailbox on
// a page load (extensions.js).

// The text criteria, each sent to /email-box/search under its own name.
export const TEXT_CRITERIA = ['from', 'to', 'words'];

// The address's parameters, by criterion. Prefixed, so that none of them reads as one
// of the page's own or of the mailbox's other deep links (folder=, mailbox=).
const URL_PARAMS = {
  term: 'search',
  from: 'searchFrom',
  to: 'searchTo',
  words: 'searchWords',
  after: 'searchAfter',
  before: 'searchBefore',
  attachment: 'searchAttachment',
  folder: 'searchFolder',
  unread: 'searchUnread',
  favorites: 'searchStarred',
};

// The longest text taken from the address: a criterion, not a document.
const MAX_TEXT_LENGTH = 200;

// A folder the search can be asked for from the address: the user's own searchable
// folders, or a folder of a mailbox shared with them.
const SEARCH_FOLDER_PATTERN = /^(INBOX|SENT|ARCHIVE|CUSTOM:\d{1,18})$/;

const DAY_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

// Whether this page's address got openEmailBox=true from a search, rather than from
// the link that loaded it: only then does clearing the search take it away again.
let openParamAddedBySearch = false;

// The same for mailbox=, the shared mailbox a search ran in.
let mailboxParamAddedBySearch = false;

/**
 * The criteria of no advanced search.
 *
 * @returns {Object} {from, to, words, after, before, attachment, folder}, all empty;
 *          folder null means the folder shown
 */
export function emptySearchCriteria() {
  return {
    from: '',
    to: '',
    words: '',
    after: null,
    before: null,
    attachment: false,
    folder: null,
  };
}

/**
 * Whether some criteria narrow a search on their own -- the folder alone does not: it
 * only says where to search.
 *
 * @param {Object} criteria the criteria
 * @returns {Boolean} true when one of them is set
 */
export function hasSearchCriteria(criteria) {
  return !!criteria && (TEXT_CRITERIA.some(name => !!(criteria[name] || '').trim())
    || !!criteria.after || !!criteria.before || !!criteria.attachment);
}

/**
 * Whether a text is a day as yyyy-MM-dd that the calendar has.
 *
 * @param {String} value the text
 * @returns {Boolean} true for a real day
 */
export function isSearchDay(value) {
  if (!DAY_PATTERN.test(value || '')) {
    return false;
  }
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

/**
 * The day after a day, as yyyy-MM-dd: the earliest "before" day a range starting on
 * that day can have, since the "before" day is excluded.
 *
 * @param {String} value a day as yyyy-MM-dd
 * @returns {String} the next day, or null when the value is no day
 */
export function nextSearchDay(value) {
  if (!isSearchDay(value)) {
    return null;
  }
  const [year, month, day] = value.split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, day + 1)).toISOString().substring(0, 10);
}

/**
 * Whether a listed row matches a search, for the instant matches drawn before any
 * answer: the search box's text over the subject and the sender, the sender, the To and
 * Cc recipients when the row carries them (a folder's listing does not: the copy's search
 * answers those), the words over the subject, and the attachments eXo listed for it.
 * Never more than the copy's own search finds (EmailBoxService#filterCached), so the
 * instant matches never show a row the answers then contradict: the words are matched
 * against the subject only, the copy's search adding the body's matches. A range of
 * days is not evaluated here -- the browser's day and eXo's server's can differ -- so
 * a search with one draws no instant match (null).
 *
 * @param {Object} email the listed row
 * @param {String} term the search box's text, lower-cased, may be empty
 * @param {Object} criteria the advanced criteria
 * @returns {Boolean} whether the row matches, or null when it cannot be told here
 */
export function listedRowMatches(email, term, criteria) {
  if (criteria?.after || criteria?.before) {
    return null;
  }
  const has = (value, text) => (value || '').toLowerCase().includes(text);
  const person = (who, text) => has(who?.name, text) || has(who?.address, text);
  const from = (criteria?.from || '').trim().toLowerCase();
  const to = (criteria?.to || '').trim().toLowerCase();
  const words = (criteria?.words || '').trim().toLowerCase();
  return (!term || has(email.subject, term) || person(email.sender, term))
    && (!from || person(email.sender, from))
    && (!to || [...(email.to || []), ...(email.cc || [])].some(recipient => person(recipient, to)))
    && (!words || has(email.subject, words))
    && (!criteria?.attachment || !!email.content?.attachments?.length);
}

/**
 * Reads the search an address asks for. The parameters are data from the address bar,
 * so each is checked: a text is trimmed and capped, a day is a real yyyy-MM-dd one, a
 * folder one of the keys a search can read, a mailbox a delegation id; anything else is
 * left out. A range whose days are in the wrong order keeps its first day only.
 *
 * @param {URLSearchParams} urlParams the page's query parameters
 * @returns {Object} {searchTerm, searchCriteria, searchUnread, searchFavorites, mailbox},
 *          or null when the address asks for no search
 */
export function searchFromUrl(urlParams) {
  const text = name => (urlParams.get(URL_PARAMS[name]) || '').trim().substring(0, MAX_TEXT_LENGTH);
  const day = name => (isSearchDay(urlParams.get(URL_PARAMS[name])) ? urlParams.get(URL_PARAMS[name]) : null);
  const criteria = emptySearchCriteria();
  TEXT_CRITERIA.forEach(name => criteria[name] = text(name));
  criteria.after = day('after');
  criteria.before = day('before');
  if (criteria.after && criteria.before && criteria.before <= criteria.after) {
    criteria.before = null;
  }
  criteria.attachment = urlParams.get(URL_PARAMS.attachment) === 'true';
  const folder = urlParams.get(URL_PARAMS.folder) || '';
  criteria.folder = SEARCH_FOLDER_PATTERN.test(folder) ? folder : null;
  const searchTerm = text('term');
  const searchUnread = urlParams.get(URL_PARAMS.unread) === 'true';
  const searchFavorites = urlParams.get(URL_PARAMS.favorites) === 'true';
  // Another folder with Unread or Favorites is a search too (the mailbox drawer's
  // advancedSearchActive); a folder alone is not.
  if (!searchTerm && !hasSearchCriteria(criteria) && !(criteria.folder && (searchUnread || searchFavorites))) {
    return null;
  }
  const mailbox = urlParams.get('mailbox') || '';
  return {
    searchTerm,
    searchCriteria: criteria,
    searchUnread,
    searchFavorites,
    mailbox: /^\d{1,18}$/.test(mailbox) ? mailbox : null,
  };
}

/**
 * Writes a search into the page's address, in place of the one there, without a new
 * history entry: reloading the page, or opening the address elsewhere, opens the
 * mailbox on the same search. The mailbox is named when the search runs in a mailbox
 * shared with the user.
 *
 * @param {Object} search {term, criteria, unread, favorites, mailbox}
 * @returns {void}
 */
export function writeSearchToUrl(search) {
  const url = new URL(window.location.href);
  removeSearchParams(url.searchParams);
  if (url.searchParams.get('openEmailBox') !== 'true') {
    url.searchParams.set('openEmailBox', 'true');
    openParamAddedBySearch = true;
  }
  const criteria = search.criteria || emptySearchCriteria();
  const set = (name, value) => value && url.searchParams.set(URL_PARAMS[name], value);
  set('term', (search.term || '').trim());
  TEXT_CRITERIA.forEach(name => set(name, (criteria[name] || '').trim()));
  set('after', criteria.after);
  set('before', criteria.before);
  set('attachment', criteria.attachment && 'true');
  set('folder', criteria.folder);
  set('unread', search.unread && 'true');
  set('favorites', search.favorites && 'true');
  if (search.mailbox && url.searchParams.get('mailbox') !== String(search.mailbox)) {
    url.searchParams.set('mailbox', String(search.mailbox));
    mailboxParamAddedBySearch = true;
  } else if (!search.mailbox) {
    // A search in the user's own mailbox: a mailbox= left there would reopen another one.
    url.searchParams.delete('mailbox');
    mailboxParamAddedBySearch = false;
  }
  replaceUrl(url);
}

/**
 * Takes the search out of the page's address, and the mailbox opening and the shared
 * mailbox's name with it when it was the search that put them there.
 *
 * @returns {void}
 */
export function clearSearchFromUrl() {
  const url = new URL(window.location.href);
  const hadSearch = Object.values(URL_PARAMS).some(name => url.searchParams.has(name));
  if (!hadSearch) {
    return;
  }
  removeSearchParams(url.searchParams);
  if (mailboxParamAddedBySearch) {
    url.searchParams.delete('mailbox');
    mailboxParamAddedBySearch = false;
  }
  if (openParamAddedBySearch) {
    url.searchParams.delete('openEmailBox');
    openParamAddedBySearch = false;
  }
  replaceUrl(url);
}

/**
 * Removes every search parameter from a set of query parameters.
 *
 * @param {URLSearchParams} params the parameters, changed in place
 * @returns {void}
 */
function removeSearchParams(params) {
  Object.values(URL_PARAMS).forEach(name => params.delete(name));
}

/**
 * Replaces the page's address, keeping its history state.
 *
 * @param {URL} url the new address
 * @returns {void}
 */
function replaceUrl(url) {
  if (url.href !== window.location.href) {
    window.history.replaceState(window.history.state, '', url.href);
  }
}
