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
 * Updates an address-book (CardDAV) contact by pushing the edit to the
 * server first: the server merges the edit into its own card and keeps it
 * only if nobody changed the entry in the meantime. A rejection with status
 * 409 and the conflict message code means somebody did — the server's card
 * has become the local row again, and the user's edit is theirs to retry.
 *
 * @param {object} contact - the contact, with its id
 * @returns {Promise<object>} the updated contact, re-read after the push
 */
export function updateAddressBookContact(contact) {
  return saveContact(`/email-connector/rest/contacts/${contact.id}/card`, 'PUT', contact);
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
 * One contact as text-only vCard — what the card's QR code encodes. The
 * server strips the photo; a 404 covers a missing and a foreign contact alike.
 *
 * @param {number} id - the contact id
 * @returns {Promise<string>} the vCard text
 */
export function getContactVCard(id) {
  return fetch(`/email-connector/rest/contacts/${id}/vcard`, {
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.text();
    } else {
      throw new Error('Error when reading the contact vCard');
    }
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
 * Publishes one of the caller's contacts into their CardDAV address book —
 * creates only: the server stores the card under If-None-Match:*, so nothing
 * on the server can ever be overwritten by this call.
 *
 * @param {number} id - the contact id
 * @returns {Promise<object>} the contact re-read, now an address-book row; or
 *          `{queued: true}` when the server was unreachable and the publish
 *          was queued to retry after the next successful sync (HTTP 202 —
 *          accepted, not done, not an error). A rejection carries the server's
 *          message code and status (409 = an entry already exists, 400 = a
 *          guard refused)
 */
export function publishContact(id) {
  return fetch(`/email-connector/rest/contacts/${id}/publish`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST'
  }).then((resp) => {
    if (resp?.status === 202) {
      // Not the contact: the publish is queued, and the contact is unchanged
      // until the queue lands it. Resolved rather than rejected because the
      // click succeeded at the only thing still possible -- not losing it.
      return resp.json().catch(() => ({})).then(body => ({...body, queued: true}));
    }
    if (resp?.ok) {
      return resp.json();
    }
    return resp.text().then(message => {
      const error = new Error(message || 'Error when publishing the contact');
      error.status = resp.status;
      throw error;
    });
  });
}

/**
 * The contacts the bulk checklist may offer.
 * <p>
 * MANUAL is the whole rule, and it is the server's: a collected row is a
 * by-product of mail traffic the user never chose to keep and a directory
 * colleague already lives in the platform, so the publish endpoint refuses
 * both, while an address-book row is skipped as already there. Asking for that
 * one source is how this list stays exactly what the server would accept
 * instead of a second copy of the rule that can drift from it.
 * <p>
 * Read fresh every time the drawer opens rather than filtered from the list in
 * memory: that list is paged and narrowed by whatever the user was browsing,
 * and a checklist that silently omitted somebody would be worse than none.
 *
 * @returns {Promise<Array>} the publishable contacts, in the store's sort order
 */
export function getPublishCandidates() {
  return fetch('/email-connector/rest/contacts?offset=0&limit=1000&source=MANUAL', {
    credentials: 'include'
  }).then(resp => {
    if (!resp?.ok) {
      throw new Error('Error when listing the contacts to publish');
    }
    return resp.json();
  }).then(body => body?.contacts || []);
}

/**
 * Queues a reviewed selection to publish.
 * <p>
 * The queue answers, not the server: what comes back says what is waiting, so
 * the caller reports contacts accepted for publishing rather than claiming
 * they already reached the address book.
 *
 * @param {Array<Number>} contactIds the reviewed ids
 * @returns {Promise<Object>} the stored queue
 */
export function queuePublishes(contactIds) {
  return fetch('/email-connector/rest/contacts/carddav/publish-queue', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST',
    body: JSON.stringify(contactIds)
  }).then(resp => {
    if (!resp?.ok) {
      return resp.text().then(message => {
        const error = new Error(message || 'Error when queueing the contacts to publish');
        error.status = resp.status;
        throw error;
      });
    }
    return resp.json();
  });
}

/** The one in-flight or settled answer of {@link isAddressBookPublishable}. */
let publishablePromise = null;

/**
 * Whether the caller can publish contacts to an address book at all — what the
 * contact card asks before showing its publish action. Cached for the page's
 * life: the answer changes when the user rebinds their mailbox or an admin
 * flips the kill switch, both of which come with a reload's worth of change.
 *
 * @returns {Promise<boolean>} true when the publish action applies
 */
export function isAddressBookPublishable() {
  if (!publishablePromise) {
    publishablePromise = fetch('/email-connector/rest/contacts/carddav/publishable', {
      credentials: 'include',
      method: 'GET'
    }).then((resp) => {
      if (!resp?.ok) {
        throw new Error('Error when asking whether publishing is available');
      }
      return resp.json();
    }).then(answer => !!answer?.available)
      .catch(() => {
        // An unanswered question is not a "no" forever: forget the attempt so
        // the next card asks again, and hide the action meanwhile.
        publishablePromise = null;
        return false;
      });
  }
  return publishablePromise;
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



/**
 * Starts importing an uploaded .vcf into the caller's own store. The server
 * answers immediately and runs the import in the background — poll
 * {@link getImportStatus} until its status leaves IN_PROGRESS.
 *
 * @param {string} uploadId - the upload id the file was pushed under
 * @returns {Promise<object>} the initial import state, or a rejection whose
 *          message is the server's message code (409 = an import already runs)
 */
export function importContacts(uploadId) {
  return fetch(`/email-connector/rest/contacts/import?uploadId=${encodeURIComponent(uploadId)}`, {
    credentials: 'include',
    method: 'POST'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.text().then(message => {
      const error = new Error(message || 'Error when importing contacts');
      error.status = resp.status;
      throw error;
    });
  });
}

/**
 * Reads the first card of an uploaded .vcf as the contact it would become —
 * fields only, nothing persisted, no photo. This is the prefill of the
 * add-contact form: the user confirms, and only the confirming save creates
 * anything. The upload is consumed either way.
 *
 * @param {string} uploadId - the upload id the file was pushed under
 * @returns {Promise<object>} the would-be contact, or a rejection whose
 *          message is the server's message code (missing upload, oversized
 *          file, no readable card)
 */
export function parseVCardFile(uploadId) {
  return fetch(`/email-connector/rest/contacts/parse?uploadId=${encodeURIComponent(uploadId)}`, {
    credentials: 'include',
    method: 'POST'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    }
    return resp.text().then(message => {
      const error = new Error(message || 'Error when parsing the vCard file');
      error.status = resp.status;
      throw error;
    });
  });
}

/**
 * How the caller's vCard import is going, or went: the status, the four
 * counters, and the message code of whatever cut a run short.
 *
 * @returns {Promise<object>} the stored import state; a null status says no
 *          import ever ran
 */
export function getImportStatus() {
  return fetch('/email-connector/rest/contacts/import/status', {
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when reading the import status');
    }
  });
}

/**
 * Downloads the caller's whole store as one .vcf file — always the whole
 * store, never the filtered view, which is why this takes no parameters. The
 * browser handles the download itself: the endpoint answers an attachment,
 * and the session cookie is all the authentication it needs.
 *
 * @returns {void}
 */
export function downloadExport() {
  const link = document.createElement('a');
  link.href = '/email-connector/rest/contacts/export';
  link.download = 'contacts.vcf';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
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
  ], exoi18n => bootstrapMailApp(exoi18n).then(() =>
    document.dispatchEvent(new CustomEvent('open-email-composer', {
      detail: {to: [recipient]},
    }))));
}

/**
 * Opens the email composer with the contact's own vCard already attached and
 * nobody addressed — sharing a person is the user's act, and who receives them
 * is the user's next decision, not this card's.
 * <p>
 * The card travels as a download URL, not as bytes: the composer already knows
 * how to turn a URL into a commons upload id (the very path a file picked from
 * Documents takes), so this rides the existing
 * 'open-email-compose-with-attachment' door rather than opening a second one.
 * photo=true asks the full card — an attachment has none of the QR's byte
 * budget, and the server still keeps a colleague's platform avatar to itself.
 *
 * @param {object} contact - the contact to share, with its id and displayName
 * @returns {void}
 */
export function sendByEmail(contact) {
  window.require([
    'SHARED/eXoVueI18n',
    'PORTLET/email-connector/EmailConnectorUserSetting',
    'SHARED/emailConnectorQuickActionExtension',
  ], exoi18n => bootstrapMailApp(exoi18n).then(() =>
    document.dispatchEvent(new CustomEvent('open-email-compose-with-attachment', {
      detail: {attachment: toVCardAttachment(contact)},
    }))));
}

/**
 * The composer-shaped attachment descriptor of a contact's vCard. Named for a
 * human — the person's name, .vcf — with only what no filesystem accepts
 * stripped; the size stays 0 because the card is only fetched when the
 * composer materialises the URL, and an unknown size simply shows no size.
 *
 * @param {object} contact - the contact to share
 * @returns {object} the attachment descriptor the composer seeds itself with
 */
function toVCardAttachment(contact) {
  const name = `${(contact.displayName || contact.primaryEmail || 'contact')
    .replace(/[\\/:*?"<>|]/g, ' ').replace(/\s+/g, ' ').trim() || 'contact'}.vcf`;
  return {
    id: null,
    name,
    title: name,
    mimeType: 'text/vcard',
    mimetype: 'text/vcard',
    size: 0,
    downloadUrl: `/email-connector/rest/contacts/${contact.id}/vcard?photo=true`,
  };
}

/**
 * Mounts the mailbox app if this page never loaded it — the shared doorstep of
 * every "open the composer from the contacts app" path.
 *
 * @param {object} exoi18n - the platform i18n loader
 * @returns {Promise<void>} resolves once the mailbox app is on the page
 */
async function bootstrapMailApp(exoi18n) {
  const appId = 'emailConnector-contacts-compose';
  // Every way the mail app can already be on this page, including the mail page
  // itself, where it is mounted as a portlet under its own root id. Missing that
  // one mounted a SECOND app: both instances listened for the compose event and
  // both handed the composer the attachment, so a shared contact arrived twice.
  const mounted = document.querySelector(`#emailConnectorMailBox, #emailConenctor-mailBox-quick-actions, #${appId}`);
  if (!mounted) {
    const parent = document.createElement('div');
    parent.id = appId;
    document.querySelector('#vuetify-apps').appendChild(parent);
    await initEmailApp(appId, exoi18n);
  }
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

/**
 * Whether the chat is on this platform, hence whether a contact can be sent
 * into a conversation at all.
 * <p>
 * Detected rather than declared, the same way the composer detects Documents:
 * the chat add-on publishes its service on the shared Vue prototype when it
 * boots, so its absence is simply the action not being offered. No compile-time
 * dependency, no gatein dependency.
 *
 * @returns {boolean} true when the chat add-on is deployed
 */
export function isChatDeployed() {
  return !!Vue.prototype.$matrixService;
}

/**
 * Sends a contact into a chat conversation as a .vcf file message.
 * <p>
 * Which conversation is the chat's question to ask, not ours: this hands over a
 * File and nothing else, and the chat opens its own picker, applies its own
 * upload cap and sends. The card travels without its photo — the text-only
 * shape — because that is what the vCard endpoint hands out by default and a
 * chat message is read, not printed.
 *
 * @param {object} contact - the contact to share, with its id and displayName
 * @returns {Promise<void>} resolves once the chat has been handed the card
 */
export function sendByChat(contact) {
  return getContactVCard(contact.id).then(vcard => {
    const name = `${(contact.displayName || contact.primaryEmail || 'contact').replace(/[\\/:*?"<>|]/g, '')}.vcf`;
    const file = new File([vcard], name, {type: 'text/vcard'});
    // A file, not a link: a contact card exists nowhere but here — it is
    // generated from a row only its owner can see, so there is nothing to point
    // at. A document goes the other way and travels as a link.
    document.dispatchEvent(new CustomEvent('meeds-chat-share', {detail: {file}}));
  });
}
