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
 * One page of the caller's own contact store, with the letter-index map and
 * the filtered total.
 *
 * @param {string} query - free text matched against names and addresses
 * @param {number} offset - row offset, a multiple of limit
 * @param {number} limit - page size
 * @param {Array} source - where the contacts came from: any of collected, manual, addressBook; empty for every source
 * @param {boolean} favorites - keep only the starred contacts, intersected with the source filter
 * @returns {Promise<object>} the page {contacts, letterIndex, size, offset, limit}
 */
export function getContacts(query, offset, limit, source, favorites) {
  const params = new URLSearchParams();
  if (query) {
    params.append('q', query);
  }
  // Repeated rather than joined: the chips are multi-select, and one value could
  // only ever carry one of them.
  const sources = Array.isArray(source) && source || source && [source] || [];
  sources.forEach(value => params.append('source', value));
  if (favorites) {
    params.append('favorites', 'true');
  }
  params.append('offset', offset || 0);
  params.append('limit', limit || 100);
  return fetch(`/email-connector/rest/contacts?${params}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting contacts');
    }
  });
}

/**
 * One contact, enriched with the platform profile when the address belongs to
 * a colleague.
 *
 * @param {number} id - the contact id
 * @returns {Promise<object>} the contact
 */
export function getContact(id) {
  return fetch(`/email-connector/rest/contacts/${id}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting the contact');
    }
  });
}

/**
 * The contact reachable at an address, or null when nobody is stored there.
 * <p>
 * A 404 is the ordinary answer here, not a failure: it is how the mailbox knows
 * to offer creating the person instead of opening them.
 *
 * @param {string} address - the address as the mail carries it
 * @returns {Promise<object>} the contact, or null
 */
export function getContactByAddress(address) {
  return fetch(`/email-connector/rest/contacts/by-address?email=${encodeURIComponent(address)}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else if (resp?.status === 404) {
      return null;
    } else {
      throw new Error('Error when getting the contact at this address');
    }
  });
}

/**
 * Creates a manual contact. A previously removed collected contact with the
 * same address is revived server-side, so the caller always sees a create.
 *
 * @param {object} contact - what the user typed
 * @returns {Promise<object>} the created contact, or a rejection whose message
 *          is the server's message code (409 = a visible row already exists)
 */
export function createContact(contact) {
  return saveContact('/email-connector/rest/contacts', 'POST', contact);
}

/**
 * Updates a contact.
 *
 * @param {object} contact - the contact, with its id
 * @returns {Promise<object>} the updated contact
 */
export function updateContact(contact) {
  return saveContact(`/email-connector/rest/contacts/${contact.id}`, 'PUT', contact);
}

/**
 * Sends a create/update body and surfaces the server's message code on
 * failure, so the form can tell a conflict from a bad address.
 *
 * @param {string} url - the endpoint
 * @param {string} method - POST or PUT
 * @param {object} contact - the body
 * @returns {Promise<object>} the saved contact
 */
function saveContact(url, method, contact) {
  return fetch(url, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method,
    body: JSON.stringify(contact)
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.text().then(message => {
      const error = new Error(message || 'Error when saving the contact');
      error.status = resp.status;
      throw error;
    });
  });
}

/**
 * Deletes a contact. The server decides between a real delete (manual rows)
 * and a suppression (collected rows), and says which happened.
 *
 * @param {number} id - the contact id
 * @returns {Promise<object>} {id, suppressed}
 */
export function deleteContact(id) {
  return fetch(`/email-connector/rest/contacts/${id}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'DELETE'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when deleting the contact');
    }
  });
}

/**
 * Un-suppresses a contact — the undo of deleting a collected one.
 *
 * @param {number} id - the contact id
 * @returns {Promise<object>} the restored contact
 */
export function restoreContact(id) {
  return fetch(`/email-connector/rest/contacts/${id}/restore`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when restoring the contact');
    }
  });
}



// How many of the contact's addresses correspondence covers, and how many mails
// each of the (two per address) searches may return. Three addresses is the
// whole address list of practically every contact, and it bounds the worst case
// at six IMAP searches — the section is opened by hand, never in a loop.
const CORRESPONDENCE_ADDRESSES_MAX = 3;
const CORRESPONDENCE_PAGE_SIZE = 10;

/**
 * The recent mail exchanged with a person, newest first: what they sent the user
 * (INBOX, pinned by sender) and what the user sent them (SENT, pinned by To/Cc
 * recipient — in that folder every sender is the user, so a sender criterion
 * could never say "to this person").
 *
 * One request per direction per address, all in parallel, and one failed
 * search does not lose the others — the section degrades to whatever could be
 * read, because a card without correspondence is still a useful card. Only
 * when EVERY search failed does this reject, so the card can stay quiet about
 * it.
 *
 * @param {Array} addresses - the contact's addresses, primary first; only the
 *          first few are searched (see the cap above)
 * @returns {Promise<object>} {results, hasMore} — results carry a direction
 *          ('sent' or 'received') on top of the search row fields, deduplicated
 *          (one mail can match two addresses of the same person) and capped to
 *          one page; hasMore says the mailbox holds more than what is shown
 */
export function getCorrespondence(addresses) {
  const searched = (addresses || []).filter(address => address).slice(0, CORRESPONDENCE_ADDRESSES_MAX);
  const searches = [];
  searched.forEach(address => {
    searches.push(searchMail({from: address, folder: 'INBOX'}));
    searches.push(searchMail({to: address, folder: 'SENT'}));
  });
  return Promise.allSettled(searches).then(outcomes => {
    const pages = outcomes.filter(outcome => outcome.status === 'fulfilled').map(outcome => outcome.value);
    if (searches.length && !pages.length) {
      throw new Error('No correspondence search could be run');
    }
    const seen = new Set();
    const results = [];
    let totalMatches = 0;
    pages.forEach(page => {
      totalMatches += page?.totalMatches || 0;
      (page?.results || []).forEach(result => {
        // UIDs are per-folder, so the folder is part of the identity; the dedup is
        // for one mail matching two addresses of the same person, not for folders.
        const key = `${result.folder}:${result.mailRemoteId}`;
        if (!seen.has(key)) {
          seen.add(key);
          results.push({...result, direction: result.folder === 'SENT' ? 'sent' : 'received'});
        }
      });
    });
    results.sort((first, second) => new Date(second.receivedDate) - new Date(first.receivedDate));
    return {
      results: results.slice(0, CORRESPONDENCE_PAGE_SIZE),
      hasMore: totalMatches > CORRESPONDENCE_PAGE_SIZE || results.length > CORRESPONDENCE_PAGE_SIZE,
    };
  });
}

/**
 * One server-side mailbox search — the mailbox's own /search endpoint, an IMAP
 * SEARCH over the named folder.
 *
 * @param {object} criteria - {from, to, folder}, each optional
 * @returns {Promise<object>} the search page {results, totalMatches}
 */
function searchMail(criteria) {
  const params = new URLSearchParams();
  if (criteria.from) {
    params.append('from', criteria.from);
  }
  if (criteria.to) {
    params.append('to', criteria.to);
  }
  params.append('folder', criteria.folder || 'INBOX');
  params.append('limit', CORRESPONDENCE_PAGE_SIZE);
  return fetch(`/email-connector/rest/email-box/search?${params}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      // 401 is the ordinary answer for a user with no mailbox connected — still a
      // failure here, and the caller decides how quietly to take it.
      throw new Error('Error when searching the mailbox');
    }
  });
}

/**
 * Opens one mail in the mailbox's own reader, from the contact card, through
 * the same document-level door the favorites drawer and the unified search
 * use. The whole identity travels: a UID alone is only unique within its
 * folder, and a hit outside the local cache must first be pulled in — both of
 * which the mailbox knows how to handle, and neither of which the card should.
 *
 * @param {object} result - a search row: {mailRemoteId, folder, cached}
 * @returns {void}
 */
export function openMail(result) {
  window.require(['SHARED/emailConnectorQuickActionExtension'], () =>
    document.dispatchEvent(new CustomEvent('open-email-box-mail', {
      detail: {
        mailRemoteId: result?.mailRemoteId,
        folder: result?.folder,
        cached: result?.cached,
      },
    })));
}

/**
 * Opens the mailbox on a search for an address — the existing "see all" path:
 * the mailbox's own search field, which reaches the whole mailbox. Its free
 * text matches subject or sender, so this surfaces the mail the person sent;
 * the sent-to direction has no full-page home yet and the card says nothing
 * that pretends otherwise.
 *
 * @param {string} term - the text to search for, typically the primary address
 * @returns {void}
 */
export function openMailboxSearch(term) {
  window.require(['SHARED/emailConnectorQuickActionExtension'], () =>
    document.dispatchEvent(new CustomEvent('open-email-box-search', {
      detail: {term},
    })));
}

/**
 * Opens the email composer prefilled with a recipient, mounting the mailbox
 * app on demand exactly like the Documents send-by-email action does — the
 * composer belongs to the mail app and stays there.
 *
 * @param {object} recipient - {name, address}
 * @returns {void}
 */
export function composeTo(recipient) {
  window.require([
    'SHARED/eXoVueI18n',
    'PORTLET/email-connector/EmailConnectorUserSetting',
    'SHARED/emailConnectorQuickActionExtension',
  ], exoi18n => bootstrapAndCompose(exoi18n, recipient));
}

/**
 * Mounts the mailbox app if this page never loaded it, then hands the
 * recipient over through the document-level composer event.
 *
 * @param {object} exoi18n - the platform i18n loader
 * @param {object} recipient - {name, address}
 * @returns {Promise<void>} resolves once the event is dispatched
 */
async function bootstrapAndCompose(exoi18n, recipient) {
  const appId = 'emailConnector-contacts-compose';
  if (!document.querySelector('#emailConenctor-mailBox-quick-actions') && !document.querySelector(`#${appId}`)) {
    const parent = document.createElement('div');
    parent.id = appId;
    document.querySelector('#vuetify-apps').appendChild(parent);
    await initEmailApp(appId, exoi18n);
  }
  document.dispatchEvent(new CustomEvent('open-email-composer', {
    detail: {to: [recipient]},
  }));
}

/**
 * Builds the mailbox Vue app into the given mount point, with its bundles'
 * translations loaded.
 *
 * @param {string} appId - the mount element id
 * @param {object} exoi18n - the platform i18n loader
 * @returns {Promise<void>} resolves when the app is mounted
 */
function initEmailApp(appId, exoi18n) {
  const lang = eXo.env.portal.language;
  const urls = [
    `/email-connector/i18n/locale.portlet.emailConnector.emailConnectorUserSetting?lang=${lang}`,
    `/email-connector/i18n/locale.portlet.emailConnector.emailConnectorMailBox?lang=${lang}`,
  ];
  return new Promise(resolve => exoi18n.loadLanguageAsync(lang, urls)
    .then(i18n => Vue.createApp({
      template: `<email-connector-mail-box-app id="${appId}" />`,
      mounted() {
        resolve();
      },
      vuetify: Vue.prototype.vuetifyOptions,
      i18n,
    }, `#${appId}`, 'Contacts Compose To')));
}
