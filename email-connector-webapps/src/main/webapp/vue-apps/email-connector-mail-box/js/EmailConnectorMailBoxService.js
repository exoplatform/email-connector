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

const presentation = {
  class: 'fas fa-file-powerpoint',
  color: '#CB4B32',
};
const sheet = {
  class: 'fas fa-file-excel',
  color: '#217345',
};
const word = {
  class: 'fas fa-file-word',
  color: '#2A5699',
};
const image = {
  class: 'fas fa-file-image',
  color: '#999999',
};
const video = {
  class: 'fas fa-file-video',
  color: '#79577A',
};
const audio = {
  class: 'fas fa-file-audio',
  color: '#79577A',
};
const archive = {
  class: 'fas fa-file-archive',
  color: '#717272',
};
const code = {
  class: 'fas fa-file-code',
  color: '#6cf500',
};
const pdf = {
  class: 'fas fa-file-pdf',
  color: '#FF0000',
};
const text = {
  class: 'fas fa-file-alt',
  color: '#385989',
};
const illustration = {
  class: 'fas fa-file-contract',
  color: '#E79E24',
};
const file = {
  class: 'fas fa-file',
  color: '#476A9C',
};
const folder = {
  class: 'fas fa-folder',
  color: '#476A9C',
};
const attachmentMapIconsExtensions = new Map([
  ['application/pdf', pdf],
  ['application/vnd.ms-powerpoint', presentation],
  ['application/vnd.openxmlformats-officedocument.presentationml.presentation', presentation],
  ['application/vnd.oasis.opendocument.presentation', presentation],
  ['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', sheet],
  ['application/vnd.oasis.opendocument.spreadsheet', sheet],
  ['officedocument.spreadsheetml.sheet', sheet],
  ['application/vnd.ms-excel', sheet],
  ['text/csv', sheet],
  ['application/vnd.openxmlformats-officedocument.wordprocessingml.document', word],
  ['application/msword', word],
  ['application/rtf', word],
  ['application/vnd.oasis.opendocument.text', word],
  ['text/plain', text],
  ['image/webp', image],
  ['image/avif', image],
  ['image/bmp', image],
  ['image/gif', image],
  ['image/jpeg', image],
  ['image/png', image],
  ['image/tiff', image],
  ['image/svg+xml', image],
  ['video/x-msvideo', video],
  ['video/mp4', video],
  ['video/mpeg', video],
  ['video/ogg', video],
  ['video/webm', video],
  ['video/3gpp', video],
  ['video/quicktime', video],
  ['audio/mpeg', audio],
  ['audio/ogg', audio],
  ['audio/wav', audio],
  ['application/zip', archive],
  ['application/vnd.rar', archive],
  ['application/rar', archive],
  ['application/x-zip', archive],
  ['application/java-archive', archive],
  ['application/postscript', illustration],
  ['text/html', code],
  ['text/xml', code],
  ['application/xml', code],
  ['text/css', code],
  ['file', file],
  ['folder', folder],
]);
 
export function getEmailBox(folder, favoriteOnly) {
  const params = new URLSearchParams();
  if (folder && folder !== 'INBOX') {
    params.append('folder', folder);
  }
  // The favorite view: same folder listing, restricted server-side to the
  // messages carrying the IMAP \Flagged flag ('favorite').
  if (favoriteOnly) {
    params.append('starred', 'true');
  }
  const query = params.toString() ? `?${params}` : '';
  return fetch(`/email-connector/rest/email-box${query}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    // The drawer polls this while a sync runs and while categories are still being applied.
    // The response carries no cache directives, so the browser is free to serve it from its
    // own cache heuristically — the poll then repeats forever against a stale copy and the
    // list only updates when the user reloads the page by hand.
    cache: 'no-store',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting email box');
    }
  }).then((box) => {
    // Decorate each listed email with what its conversation looks like from outside
    // the folder on screen: the full cross-folder message total, so the badge shows
    // the whole conversation size (Gmail-style) rather than only the messages the
    // listing happens to hold, whether the conversation carries an unsent draft, and
    // who it is with. All three come from the same server-side summary, decorated by
    // lookup here — the row a draft belongs to is almost never the draft's own row
    // (an inbox listing holds no DRAFTS rows at all), and a DRAFTS listing holds
    // nothing but drafts, so there is nothing in the list itself to read either off.
    // Participants are stamped on every row and read only by draft ones: the server
    // only gathers them for conversations that carry a draft.
    const summaries = box?.threadSummaries || {};
    (box?.emails || []).forEach(email => {
      const summary = summaries[email.threadId];
      email.threadCount = summary?.messageCount;
      email.threadHasDraft = !!summary?.hasDraft;
      email.threadParticipants = summary?.participants || [];
    });
    return box;
  });
}

/**
 * Groups a flat, newest-first email list into conversations (threads). The group key
 * is the server threadId, falling back to the message id then the remote id so rows
 * still render during the one-sync-cycle backfill window when threadId can be null.
 * The input order is preserved, so each thread's first email is its latest and the
 * threads themselves come out ordered by their most recent message.
 *
 * @param {Array} emails the flat email list, newest first
 * @returns {Array} threads, each { threadId, emails, latest, mailRemoteIds, count, unreadCount, hasDraft, participants }
 */
export function groupEmailsByThread(emails) {
  const byKey = new Map();
  (emails || []).forEach(email => {
    const key = email.threadId || email.mailHeaderId || String(email.mailRemoteId);
    let thread = byKey.get(key);
    if (!thread) {
      thread = { threadId: key, emails: [], latest: email, mailRemoteIds: [], count: 0, unreadCount: 0, inboxCount: 0, hasDraft: false, participants: [] };
      byKey.set(key, thread);
    }
    thread.emails.push(email);
    thread.mailRemoteIds.push(email.mailRemoteId);
    thread.inboxCount++;
    // The badge shows the whole conversation total (all folders) that the server stamped
    // on each email; fall back to the inbox count until that arrives. mailRemoteIds /
    // unreadCount stay inbox-scoped since list actions act on the inbox.
    thread.count = email.threadCount || thread.inboxCount;
    // Whether the conversation has a reply the user never sent. Server-stamped like
    // the count and for the same reason — the draft is in DRAFTS, and this listing
    // holds one folder's rows. Accumulated rather than assigned so a row that reached
    // the fallback grouping key (no threadId yet, so no summary either) cannot clear
    // what a sibling row already reported.
    thread.hasDraft = thread.hasDraft || !!email.threadHasDraft;
    // Who the conversation is with, out of the same summary and accumulated for the
    // same reason: a row that fell back to the grouping key carries no summary and
    // must not blank what a sibling row already reported. First non-empty wins —
    // every row of a group shares one threadId and therefore one summary, so there
    // is no second answer to choose between.
    if (!thread.participants.length && email.threadParticipants?.length) {
      thread.participants = email.threadParticipants;
    }
    if (!email.read) {
      thread.unreadCount++;
    }
  });
  return Array.from(byKey.values());
}

/**
 * The email categories a user can assign (Important / Invitation / Notification),
 * each { id, name }.
 *
 * @returns {Promise<Array>} the assignable email categories
 */
export function getAvailableEmailCategories() {
  return fetch('/email-connector/rest/email-box/categories/available', {
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    method: 'GET'
  }).then(resp => (resp?.ok ? resp.json() : []));
}

/**
 * Tag the given messages (by IMAP id) with a category — pass a conversation's message
 * ids to categorize the whole thread.
 *
 * @param {Array<Number>} mailRemoteIds the messages to tag
 * @param {Number} categoryId the category id
 * @returns {Promise} resolves with the count of newly-tagged emails
 */
export function linkEmailsToCategory(mailRemoteIds, categoryId) {
  return fetch(`/email-connector/rest/email-box/categories/${categoryId}`, {
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    method: 'POST',
    body: JSON.stringify(mailRemoteIds)
  }).then(resp => {
    if (!resp?.ok) {
      throw new Error('Error when tagging emails');
    }
    return resp.json();
  });
}

/**
 * Remove a category from the given messages (by IMAP id).
 *
 * @param {Array<Number>} mailRemoteIds the messages to untag
 * @param {Number} categoryId the category id
 * @returns {Promise} resolves with the count of untagged emails
 */
export function unlinkEmailsFromCategory(mailRemoteIds, categoryId) {
  return fetch(`/email-connector/rest/email-box/categories/${categoryId}`, {
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    method: 'DELETE',
    body: JSON.stringify(mailRemoteIds)
  }).then(resp => {
    if (!resp?.ok) {
      throw new Error('Error when removing category from emails');
    }
    return resp.json();
  });
}

export function getEmailByRemoteId(mailRemoteId, folder) {
  const query = folder && folder !== 'INBOX' ? `?folder=${encodeURIComponent(folder)}` : '';
  return fetch(`/email-connector/rest/email-box/${mailRemoteId}${query}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting email detail');
    }
  });
}

export function getThreadByThreadId(threadId) {
  return fetch(`/email-connector/rest/email-box/thread/${encodeURIComponent(threadId)}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting the conversation');
    }
  });
}

/**
 * Completes a conversation from the provider's archive (Gmail All Mail) and returns
 * the whole thread. Slower than getThreadByThreadId (it may hit IMAP), so the reader
 * calls it in the background after rendering the cached thread.
 *
 * @param {String} threadId the conversation id
 * @returns {Promise<Array>} the thread including any recovered archived messages
 */
export function completeThreadByThreadId(threadId) {
  return fetch(`/email-connector/rest/email-box/thread/${encodeURIComponent(threadId)}/complete`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when completing the conversation');
    }
  });
}

/**
 * Suggests recipients for a compose field: one ranked, de-duplicated list
 * merging the user's own contact store with the platform's people directory.
 * A blank term answers their top contacts rather than nothing, so opening the
 * field already offers the people they usually write to.
 *
 * @param {String} query what the user typed, may be blank
 * @param {Number} limit how many suggestions to ask for; the server caps it
 * @returns {Promise} resolves with an array of
 *          {address, displayName, avatarUrl, platformUser, profileUrl}
 */
export function suggestRecipients(query, limit) {
  const params = new URLSearchParams();
  if (query) {
    params.append('q', query);
  }
  params.append('limit', limit || 0);
  return fetch(`/email-connector/rest/contacts/suggest?${params}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when suggesting recipients');
    }
  });
}

/**
 * Searches the WHOLE mailbox on the server (IMAP SEARCH over the remote folder),
 * not just the locally-cached window. Matches subject or sender, like the local
 * instant filter, so the two result sets agree. Each hit carries a `cached` flag
 * telling whether the message can be opened straight from the local cache.
 *
 * @param {String} query free text matched against subject or sender
 * @param {String} folder the folder to search: INBOX, SENT or ARCHIVE
 * @param {Number} limit how many hits to return (newest first)
 * @returns {Promise} resolves with { results, totalMatches }
 */
export function searchEmails(query, folder, limit) {
  const params = new URLSearchParams({ query, limit });
  if (folder && folder !== 'INBOX') {
    params.append('folder', folder);
  }
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
      throw new Error('Error when searching the mailbox');
    }
  });
}

/**
 * Fetches a search hit that lives OUTSIDE the locally-cached window: pulls that
 * one message from the server into the cache so the regular reader can open it.
 * While a synchronization is running the server refuses with 409 on purpose (it
 * would race the sync into duplicate rows); the rejection then carries
 * `status = 409` so the caller can retry after a short delay instead of
 * reporting an error.
 *
 * @param {Number} mailRemoteId the message's IMAP UID in the folder
 * @param {String} folder the folder the search hit came from
 * @returns {Promise} resolves with the full cached email
 */
export function fetchSearchedEmail(mailRemoteId, folder) {
  const query = folder && folder !== 'INBOX' ? `?folder=${encodeURIComponent(folder)}` : '';
  return fetch(`/email-connector/rest/email-box/search/${mailRemoteId}${query}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    }
    const error = new Error('Error when fetching the searched email');
    error.status = resp?.status;
    throw error;
  });
}

export function deleteEmails(mailRemoteIds) {
  return fetch('/email-connector/rest/email-box', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'DELETE',
    body: JSON.stringify(mailRemoteIds)
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when deleting emails');
    }
    return resp.json();
  });
}

export function archiveEmails(mailRemoteIds) {
  return fetch('/email-connector/rest/email-box/archive', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'DELETE',
    body: JSON.stringify(mailRemoteIds)
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when archiving emails');
    }
    return resp.json();
  });
}

export function synchronize() {
  return fetch('/email-connector/rest/email-box/synchronization', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when synchronizing email box');
    }
  });
}

/**
 * Sends (or replies/forwards) an email. The whole email object is serialized, so
 * it also carries email.attachments = [{ uploadId, name, mimeType, size }], the
 * commons upload ids the backend resolves to bytes and attaches to the message.
 *
 * @param {Object} email the composed email, including its optional attachments
 * @returns {Promise} resolves once the email has been sent
 */
export function sendEmail(email) {
  return fetch('/email-connector/rest/email-box/send', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST',
    body: JSON.stringify(email)
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when sending email');
    }
  });
}

/**
 * Saves a draft.
 *
 * The `push` flag is the whole rhythm of the feature in one boolean. Without it
 * the call only writes the row here, which is instant and free and is what the
 * composer asks for on every pause in typing — that is what protects the user's
 * words. With it, the draft also goes up to the mail server's Drafts folder, so
 * the user's other mail clients can see it; that costs a full re-upload of the
 * message (IMAP has no update), so the composer only asks for it on close, before
 * a send, and after a couple of minutes of real inactivity.
 *
 * @param {Object} draft the composed draft; a blank draftLocalId starts a new one
 * @param {boolean} push whether to also upload it to the mail server
 * @returns {Promise} resolves with the draft as stored, carrying its local id,
 *          revision and state
 */
export function saveDraft(draft, push) {
  return fetch(`/email-connector/rest/email-box/drafts?push=${!!push}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST',
    body: JSON.stringify(draft)
  }).then((resp) => {
    // 404 is not an error here, it is an answer: the draft this save was meant for
    // has been sent or discarded since the request left. Resolving with null lets the
    // composer forget it instead of retrying against an id that names nothing.
    if (resp?.status === 404) {
      return null;
    }
    if (!resp?.ok) {
      throw new Error('Error when saving draft');
    }
    return resp.json();
  });
}

/**
 * Sends a draft.
 *
 * Not the ordinary send followed by a tidy-up from here: the save, the send and the
 * removal of both copies happen on the server, in one order, because what has to be
 * true if a step fails halfway is not something a browser can hold together. The
 * body is what the composer is showing — that text is written to the draft before
 * anything is transmitted.
 *
 * @param {string} draftLocalId the draft's local id
 * @param {Object} draft the composed draft as the composer is showing it
 * @returns {Promise} resolves once the mail is out and the draft is gone
 */
export function sendDraft(draftLocalId, draft) {
  return fetch(`/email-connector/rest/email-box/drafts/${encodeURIComponent(draftLocalId)}/send`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST',
    body: JSON.stringify(draft)
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when sending draft');
    }
  });
}

/**
 * Discards a draft.
 *
 * @param {string} draftLocalId the draft's local id
 * @returns {Promise} resolves once the draft is gone
 */
export function deleteDraft(draftLocalId) {
  return fetch(`/email-connector/rest/email-box/drafts/${encodeURIComponent(draftLocalId)}`, {
    credentials: 'include',
    method: 'DELETE'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when deleting draft');
    }
  });
}

export function broadcastAccessWebmail() {
  return fetch('/email-connector/rest/email-box/webmail/broadcast', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when broadcasting access to webmail');
    }
  });
}

export function formatDateString(dateToFormat, yesterdayLabel, atLabel, fullDate) {
  const today = new Date();
  today.setHours(0,0,0,0);
  const resetDateToFormat = new Date(dateToFormat);
  resetDateToFormat.setHours(0,0,0,0);
  let options = {};
  const localeOfUser = eXo.env.portal.language.replace('_', '-');
  const differenceInDays = Math.abs(today.getTime() - resetDateToFormat.getTime()) / (24*60*60*1000);

  if (fullDate) {
    const dateOptions = {
      weekday: 'short',
      day: 'numeric',
      month: 'short',
      year: 'numeric'
    };
    const timeOptions = {
      hour: '2-digit',
      minute: '2-digit'
    };
    const datePart = new Intl.DateTimeFormat(localeOfUser, dateOptions).format(new Date(dateToFormat));
    const timePart = new Intl.DateTimeFormat(localeOfUser, timeOptions).format(new Date(dateToFormat));
    return atLabel ? `${datePart} ${atLabel} ${timePart}` : `${datePart} ${timePart}`;
  }

  if (differenceInDays === 0) { // In today
    options = {
      hour: '2-digit', 
      minute: '2-digit'
    };
    return new Date(dateToFormat).toLocaleTimeString(localeOfUser, options);
  }
  else if (differenceInDays === 1) { // In yesterday
    return yesterdayLabel;
  }
  else if (differenceInDays < 7) { // In the same week
    options = {
      weekday: 'long'
    };
    return new Date(resetDateToFormat).toLocaleDateString(localeOfUser, options).replace(/^\p{L}/u, c => c.toUpperCase());
  } else if (differenceInDays < 31) {// In the last 31 days
    options = {
      weekday: 'short',
      style: 'short',
      month: 'short',
      day: 'numeric',
    };
    return new Date(resetDateToFormat.getTime()).toLocaleDateString(localeOfUser, options);
  } else {
    options = {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric'
    };
    return new Date(resetDateToFormat.getTime()).toLocaleDateString(localeOfUser, options);
  }
}

export function updateEmailsReadStatus(mailRemoteIds, readStatus) {
  return fetch(`/email-connector/rest/email-box?readStatus=${readStatus}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PATCH',
    body: JSON.stringify(mailRemoteIds)
  }).then(resp => {
    if (!resp?.ok) {
      throw new Error('Error when updating emails read status');
    }
  });
}

/**
 * Favorites or unfavorites messages (by IMAP id): sets or clears the mail server's own
 * \Flagged flag, so the change shows in every client reading this mailbox. The
 * server updates its local mirror first, then pushes each flag to IMAP, and
 * REVERTS the local change of every message the server refused — the answer's
 * failedUpdates says how many. The caller must reflect that outcome instead of
 * assuming the push succeeded: a favorite left lit that the server rejected would
 * silently vanish at the next synchronization.
 *
 * @param {Array<Number>} mailRemoteIds the INBOX IMAP UIDs of the messages
 * @param {Boolean} favorite true to favorite, false to unfavorite
 * @returns {Promise} resolves with { failedUpdates }
 */
export function updateEmailsFavoriteStatus(mailRemoteIds, favorite) {
  // The endpoint keeps the server's own vocabulary: it pushes the IMAP \Flagged
  // flag, so its path and parameter are named after it. Only what the user reads
  // says "favorite".
  return fetch(`/email-connector/rest/email-box/starred?starred=${favorite}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PATCH',
    body: JSON.stringify(mailRemoteIds)
  }).then(resp => {
    if (!resp?.ok) {
      throw new Error('Error when updating emails favorite status');
    }
    return resp.json();
  });
}

export function getAttachmentIcon(mimeType) {
  return attachmentMapIconsExtensions.get(mimeType.toLowerCase()) || file;
}

export async function downloadAttachment(attachment, signal) {
  const mailId = attachment.mailRemoteId;
  const attachId = attachment.attachmentRemoteId;
  const url = `/email-connector/rest/email-box/attachments/${mailId}/${attachId}`;
  try {
    const response = await fetch(url, { signal });
    if (!response.ok) {
      throw new Error('Error when downloading attachment');
    }
    const contentDisp = response.headers.get('Content-Disposition');
    let filename = attachment.name;
    if (contentDisp) {
      const match = contentDisp.match(/filename\*?=UTF-8''([^;]+)/);
      if (match) {
        filename = decodeURIComponent(match[1]);
      }
    }
    const blob = await response.blob();
    const blobUrl = URL.createObjectURL(blob);
    triggerDownload(blobUrl, filename);
    setTimeout(() => {
      URL.revokeObjectURL(blobUrl);
    }, 60000);
  } catch (e) {
    if (e.name !== 'AbortError') {
      console.error('Error when downloading attachment:', e);
    }
  }
}

export function triggerDownload(fileUrl, filename) {
  const link = document.createElement('a');
  link.href = fileUrl;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
}

export async function getSubcategoryIds(categoryId) {
  if (!categoryId) {
    return [];
  }
  const subcategoyIds = await Vue.prototype.$categoryService.getSubcategoryIds(categoryId, {
    offset: 0,
    limit: -1,
    depth: -1
  });
  return [...new Set([categoryId, ...subcategoyIds])];
}
// Attachment types the platform can show without any copy in the Drive: the
// preview dialog shipped by social listens globally for open-attachments-preview
// and renders straight from a URL.
const URL_PREVIEWABLE_TYPES = ['image/', 'audio/', 'video/'];

/**
 * The types OnlyOffice declares it can open. Registered by the OnlyOffice add-on
 * into the shared 'documents' extension point, so this works without any build
 * dependency on documents; when that add-on isn't installed the list is empty and
 * every attachment simply keeps downloading, which is the right degradation.
 *
 * @returns {Array} the supported document type descriptors, empty when none
 */
function supportedDocumentTypes() {
  // extensionRegistry is injected into the bundle as a shared module, it is not a
  // window global: reading it from window silently yields nothing at all.
  return extensionRegistry?.loadExtensions('documents', 'supported-document-types') || [];
}

/**
 * Whether the Documents add-on is deployed, hence whether its attachments drawer
 * can be opened at all. Same probe the composer uses: the picker is contributed by
 * Documents at runtime, so its absence is detected rather than declared.
 *
 * @returns {Boolean} true when the Documents add-on is on the page
 */
export function isDocumentsDeployed() {
  return extensionRegistry?.loadExtensions('RichEditor', 'ckeditor-extensions').some(extension => extension.id === 'attachFile')
    || !!Vue.prototype.$attachmentService;
}

/**
 * The address the attachment bytes are served from. No portal context prefix: the
 * add-on mounts its own REST context, and prefixing it lands on the portal itself,
 * which answers a page with 200 instead of the file.
 *
 * @param {Object} attachment the received attachment
 * @returns {String} the URL its content can be read from
 */
export function getAttachmentUrl(attachment) {
  return `/email-connector/rest/email-box/attachments/${attachment.mailRemoteId}/${attachment.attachmentRemoteId}`;
}

/**
 * The content type of an attachment, in the shape the rest of the platform uses.
 * A mail header is not normalised: the server hands back things like 'IMAGE/PNG'
 * and may append parameters, as in 'text/plain; charset=UTF-8'. Comparing that
 * raw is why nothing ever matched, so keep every comparison going through here.
 *
 * @param {Object} attachment the received attachment
 * @returns {String} the bare content type, lower case, without its parameters
 */
function normaliseMimeType(attachment) {
  return (attachment?.mimeType || '').split(';')[0].trim().toLowerCase();
}

/**
 * Whether the platform preview dialog can render the attachment from its URL alone.
 *
 * @param {Object} attachment the received attachment
 * @returns {Boolean} true for an image, a sound or a video
 */
export function isUrlPreviewable(attachment) {
  const mimeType = normaliseMimeType(attachment);
  return URL_PREVIEWABLE_TYPES.some(type => mimeType.startsWith(type));
}

/**
 * Whether OnlyOffice declares it can open the attachment, which also means the
 * add-on is installed at all.
 *
 * @param {Object} attachment the received attachment
 * @returns {Boolean} true when a registered document type matches
 */
export function isEditorPreviewable(attachment) {
  const mimeType = normaliseMimeType(attachment);
  return supportedDocumentTypes().some(type => (type.mimeType || '').toLowerCase() === mimeType);
}

/**
 * Opens an image, audio or video attachment in the platform preview dialog,
 * straight from its URL. Nothing is written to the Drive.
 *
 * @param {Object} attachment the received attachment to preview
 * @returns {void}
 */
export function previewAttachmentFromUrl(attachment) {
  const id = String(attachment.attachmentRemoteId);
  document.dispatchEvent(new CustomEvent('open-attachments-preview', {
    detail: {
      id,
      attachments: [{
        id,
        filename: attachment.name,
        downloadUrl: getAttachmentUrl(attachment),
        // the preview dialog reads 'mimetype', not 'mimeType', and picks its
        // renderer by matching the type as-is, so hand it the normalised one
        mimetype: normaliseMimeType(attachment),
      }],
    },
  }));
}

// Where a received attachment is stored when it has to become a real document.
// Two spellings on purpose: the platform keeps the capitalised title for display
// but stores the node under a lower-cased name, and the attachments service
// resolves the destination it is given as a JCR path verbatim.
const RECEIVED_FOLDER_TITLE = 'Received';

export const ATTACHMENTS_FOLDER_TITLE = 'Mail Attachments';

const RECEIVED_FOLDER_TITLES = [ATTACHMENTS_FOLDER_TITLE, RECEIVED_FOLDER_TITLE];

// The node path 'Mail Attachments' resolves to. The platform keeps the capitalised
// title for display but stores the node under a lower-cased name, and both the ECMS
// upload endpoint and the folder picker match path segments verbatim — so a display
// title handed to either would create a second, differently-cased duplicate folder.
export const ATTACHMENTS_FOLDER_PATH = ATTACHMENTS_FOLDER_TITLE.toLowerCase();

// The user's own Drive, where received attachments land by default.
const PERSONAL_DRIVE_NAME = 'Personal Documents';

const DEFAULT_WORKSPACE = 'collaboration';

// Documents already materialised in this page, keyed by mail id + part id AND by
// destination, so opening the same attachment twice reuses its document instead of
// copying it again — while storing it somewhere else really does store it there
// rather than hand back the copy made for another folder.
const materialisedDocuments = {};

/**
 * Creates a folder in the user's Drive, ignoring the answer: it is called to make
 * sure the folder is there, and it already existing is the expected outcome.
 *
 * @param {String} ownerId the identity the Drive belongs to
 * @param {String} name the folder title
 * @param {String} parentId the id of the folder it goes in, root when absent
 * @returns {Promise} resolved once the server has answered, never rejected
 */
function createFolder(ownerId, name, parentId) {
  const params = new URLSearchParams({ ownerId, name });
  if (parentId) {
    params.append('parentid', parentId);
  } else {
    params.append('folderPath', '');
  }
  return fetch(`${eXo.env.portal.context}/${eXo.env.portal.rest}/v1/documents/folder?${params}`, {
    credentials: 'include',
    method: 'POST',
  }).catch(() => null);
}

/**
 * Looks a folder up by title among the folders of a parent.
 *
 * @param {String} ownerId the identity the Drive belongs to
 * @param {String} name the folder title to look for
 * @param {String} parentFolderId the folder to look into, root when absent
 * @returns {Promise} resolved with the folder, or null when it isn't there
 */
function findFolder(ownerId, name, parentFolderId) {
  const params = new URLSearchParams({ ownerId, listingType: 'FOLDER', limit: '200' });
  if (parentFolderId) {
    params.append('parentFolderId', parentFolderId);
  }
  return fetch(`${eXo.env.portal.context}/${eXo.env.portal.rest}/v1/documents?${params}`, {
    credentials: 'include',
    headers: { Accept: 'application/json' },
  }).then(response => response.json())
    .then(body => {
      const items = Array.isArray(body) && body || body.documents || body.items || [];
      return items.find(item => (item.name || item.title) === name);
    })
    .catch(() => null);
}

/**
 * The JCR path a chain of folder titles resolves to. The platform keeps the
 * capitalised title for display but stores the node under a lower-cased name, and
 * the attachments service resolves the destination it is given verbatim.
 *
 * @param {Array} folderTitles the folder titles, outermost first
 * @returns {String} the path to hand to the upload service
 */
function toFolderPath(folderTitles) {
  return folderTitles.map(title => title.toLowerCase()).join('/');
}

/**
 * Makes sure a whole chain of folders exists in the user's Drive, creating each
 * level under the previous one. The folders are addressed by path afterwards, so
 * this only guarantees they are there.
 *
 * @param {Array} folderTitles the folder titles, outermost first
 * @returns {Promise} resolved once every level is known to exist
 */
function ensureFolderPath(folderTitles) {
  const ownerId = eXo.env.portal.userIdentityId;
  if (!ownerId) {
    return Promise.resolve();
  }
  return ensureFolder(ownerId, folderTitles, 0, null);
}

/**
 * Creates one level of a folder chain, then goes on with the next one under it.
 * Recursive rather than a loop because a level can only be created once the id of
 * its parent is known, hence one round trip after the other.
 *
 * @param {String} ownerId the identity the Drive belongs to
 * @param {Array} folderTitles the folder titles, outermost first
 * @param {Number} index the level being created
 * @param {String} parentId the id of the level above, null at the root
 * @returns {Promise} resolved once this level and the ones below it exist
 */
function ensureFolder(ownerId, folderTitles, index, parentId) {
  const title = folderTitles[index];
  // Look the level up first and only create it when it is missing. Creating it blindly
  // works — the server answers 409 for an existing folder and fetch does not reject on
  // it — but the browser still logs that 409 to the console on every save, which reads
  // as an error to anyone watching. A lookup first keeps the console clean.
  return findFolder(ownerId, title, parentId)
    .then(existing => existing || createFolder(ownerId, title, parentId)
      .then(() => findFolder(ownerId, title, parentId)))
    .then(folder => {
      if (!folder || index === folderTitles.length - 1) {
        return null;
      }
      return ensureFolder(ownerId, folderTitles, index + 1, folder.id);
    });
}

/**
 * Reads a received attachment back as a File, the shape every upload path of the
 * platform takes, whether it is the commons upload service or the Documents drawer.
 *
 * @param {Object} attachment the received attachment
 * @returns {Promise} resolved with the attachment content as a File
 */
export async function fetchAttachmentFile(attachment) {
  const response = await fetch(getAttachmentUrl(attachment), { credentials: 'include' });
  if (!response.ok) {
    throw new Error(`Could not read the attachment (${response.status})`);
  }
  const blob = await response.blob();
  return new File([blob], attachment.name, { type: attachment.mimeType || blob.type });
}

/**
 * Copies a received attachment into the Drive and returns its document id, going
 * through the very same upload the Documents picker uses so the file is ingested
 * exactly like any other upload rather than through a side door.
 *
 * The result is memoised on the attachment AND on its destination: the same
 * attachment asked for twice is copied once, but asking for it in another folder
 * really stores it there instead of handing back the copy made for the first one.
 *
 * @param {Object} attachment the received attachment to materialise
 * @param {Array} folderTitles the destination folder titles, outermost first
 * @param {String} driveName the Drive to store into, the user's own by default
 * @param {String} workspace the JCR workspace of that Drive
 * @returns {Promise} resolved with the id of the created document
 */
export function materialiseAttachment(attachment, folderTitles = RECEIVED_FOLDER_TITLES, driveName = PERSONAL_DRIVE_NAME, workspace = DEFAULT_WORKSPACE) {
  // Only the user's own Drive is addressed by identity and can be created through
  // the documents folder REST; anywhere else the folder is one the picker returned,
  // so it already exists and there is nothing to create.
  const titlesToEnsure = driveName === PERSONAL_DRIVE_NAME && folderTitles.length ? folderTitles : null;
  return materialiseAttachmentAt(attachment, toFolderPath(folderTitles), driveName, workspace, titlesToEnsure);
}

/**
 * Copies a received attachment into an already-known Drive folder and returns its
 * document id. This is the picker-facing variant of materialiseAttachment: the
 * destination is a drive-root-relative node path used VERBATIM — exactly what the
 * revamped Documents folder picker returns — so no title lower-casing is applied
 * and, unless a folder chain is explicitly given, nothing is created: the picker
 * only ever returns folders that exist.
 *
 * @param {Object} attachment the received attachment to materialise
 * @param {String} destination the drive-root-relative node path ('' for the root)
 * @param {String} driveName the legacy ECMS Drive name to store into
 * @param {String} workspace the JCR workspace of that Drive
 * @param {Array} folderTitlesToEnsure folder titles to create first, or null
 * @returns {Promise} resolved with the id of the created document
 */
export function materialiseAttachmentAt(attachment, destination, driveName = PERSONAL_DRIVE_NAME, workspace = DEFAULT_WORKSPACE, folderTitlesToEnsure = null) {
  const key = `${attachment.mailRemoteId}/${attachment.attachmentRemoteId}@${driveName}:${workspace}:${destination}`;
  if (materialisedDocuments[key]) {
    return materialisedDocuments[key];
  }
  const promise = (async () => {
    if (folderTitlesToEnsure && folderTitlesToEnsure.length) {
      await ensureFolderPath(folderTitlesToEnsure);
    }
    const file = await fetchAttachmentFile(attachment);
    const uploadId = Vue.prototype.$uploadService.generateRandomId();
    await Vue.prototype.$uploadService.upload(file, uploadId);

    const params = new URLSearchParams({
      workspaceName: workspace,
      driveName: driveName,
      currentFolder: destination,
      currentPortal: eXo.env.portal.portalName,
      uploadId,
      fileName: attachment.name,
      language: eXo.env.portal.language,
      // keep both copies rather than overwrite a same-named file from another mail
      existenceAction: 'keep',
      action: 'save',
    });
    const saved = await fetch(`${eXo.env.portal.context}/${eXo.env.portal.rest}/managedocument/uploadFile/control?${params}`, {
      credentials: 'include',
    });
    if (!saved.ok) {
      throw new Error(`Could not store the attachment (${saved.status})`);
    }
    // the upload service answers in XML, the document id is its UUID
    const document = new DOMParser().parseFromString(await saved.text(), 'text/xml');
    const documentId = document.querySelector('UUID')?.textContent
      || document.documentElement?.getAttribute('UUID');
    if (!documentId) {
      throw new Error('The stored attachment has no document id');
    }
    return documentId;
  })();
  materialisedDocuments[key] = promise;
  promise.catch(() => delete materialisedDocuments[key]);
  return promise;
}

/**
 * The address of the OnlyOffice editor for a stored document. Built the way the
 * Documents add-on builds it: on the meta portal, since the mail box is opened
 * from any page and the current portal can be a space, which has no editor.
 *
 * @param {String} documentId the id of the stored document
 * @param {String} mode 'view' to open read only, editable when absent
 * @returns {String} the editor URL, coming back to the current page when closed
 */
export function getEditorUrl(documentId, mode) {
  const portal = eXo.env.portal.metaPortalName || eXo.env.portal.portalName;
  const modeParam = mode && `&mode=${mode}` || '';
  return `${eXo.env.portal.context}/${portal}/oeditor?docId=${documentId}${modeParam}&backTo=${window.location.pathname}`;
}

/**
 * Shows a toast through the platform's global alert bus. Used from here rather than a
 * component's $root because the save completes asynchronously, after the user has
 * picked a folder, when the click that started it is long gone.
 *
 * When a link is given the toast carries a link button (the platform alert's
 * alertLink / alertLinkText pair, opened in a new tab) so the user can jump
 * straight to what the toast talks about, e.g. the freshly stored document.
 *
 * @param {String} alertMessage the already-translated message to show
 * @param {String} alertType 'success' or 'error'
 * @param {String} alertLink the address the toast's link button opens, or null
 * @param {String} alertLinkText the already-translated link button text
 * @returns {void}
 */
function notify(alertMessage, alertType, alertLink = null, alertLinkText = null) {
  document.dispatchEvent(new CustomEvent('alert-message', {
    detail: {
      alertType,
      alertMessage,
      alertLink: alertLink || null,
      alertLinkText: alertLink && alertLinkText || null,
      alertLinkTarget: alertLink && '_blank' || null,
    },
  }));
}

/**
 * The address of the Documents application showing the given folder. A folder in a
 * space drive opens the Documents app of that space (its own context); a folder in
 * the personal drive opens the user's own Documents (their workspace's Drive, i.e.
 * eXo.env.portal.defaultPath) — NOT the meta portal, which is a different Drive the
 * saved folder does not live in. Both read folderId from the URL.
 *
 * @param {Object} pickerDetail the folder picker's selection event detail
 * @returns {String} the documents page URL for the picked folder
 */
function getDocumentsFolderUrl(pickerDetail) {
  const params = new URLSearchParams({ folderId: pickerDetail.folderId });
  if (pickerDetail.spaceId) {
    // the space's own Documents app
    return `${eXo.env.portal.context}/s/${pickerDetail.spaceId}/documents?${params}`;
  }
  // the user's personal Documents (their workspace Drive), where the folder lives
  return `${eXo.env.portal.defaultPath}/documents?${params}`;
}

/**
 * Where a completed save can be seen: the destination folder opened in Documents,
 * whether one attachment or several were saved. It opens the folder listing rather
 * than a document preview on purpose — the user asked to land in the folder, not in
 * the editor. Null — meaning a plain toast — when the picker did not hand back the
 * folder id.
 *
 * @param {Array} documentIds the ids of the stored documents
 * @param {Object} pickerDetail the folder picker's selection event detail
 * @returns {String} the URL opening the destination folder in Documents, or null
 */
function savedLocationUrl(documentIds, pickerDetail) {
  if (!pickerDetail.folderId) {
    return null;
  }
  return getDocumentsFolderUrl(pickerDetail);
}

/**
 * Saves received attachments into the Drive in one shot. Opens the reusable Documents
 * folder picker (the very same folders-only explorer Documents uses for "change
 * location"), then, on the folder the user confirms, uploads every attachment straight
 * there — no staging drawer, no second upload click. Closing the picker without
 * choosing cancels silently.
 *
 * The picker hands back BOTH the /v1/documents folder id and the legacy addressing:
 * the ECMS drive name plus the drive-root-relative node path of the chosen folder.
 * The upload endpoint (managedocument/uploadFile/control) wants exactly that legacy
 * pair, so both are used VERBATIM — the picker's answer is authoritative, nothing is
 * re-derived, lower-cased or created on top of it.
 *
 * @param {Array} attachments the received attachments to store
 * @param {Object} messages the translated toasts: { success, error, see } — see
 *                 being the success toast's link text to the saved location
 * @returns {Promise} resolved once the picker has been opened (not once saved)
 */
export async function saveAttachmentsInDocuments(attachments, messages = {}) {
  // make the default folder exist first, so the picker opens right inside it rather
  // than at the drive root; the upload would create it too, but not as the default
  await ensureFolderPath([ATTACHMENTS_FOLDER_TITLE]);

  const onCancelled = () => cleanup();
  const onSelected = (event) => {
    cleanup();
    const detail = event.detail || {};
    // drive-root-relative node path, '' when the drive root itself was picked
    const destination = detail.relativePath ?? detail.path ?? '';
    const driveName = detail.driveName || detail.drive?.name || PERSONAL_DRIVE_NAME;
    const workspace = detail.workspace || DEFAULT_WORKSPACE;
    Promise.all(attachments.map(attachment => materialiseAttachmentAt(attachment, destination, driveName, workspace)))
      // the success toast links to the result: the stored document itself for a
      // single attachment, the destination folder for a whole set
      .then(documentIds => notify(messages.success, 'success', savedLocationUrl(documentIds, detail), messages.see))
      .catch(() => notify(messages.error, 'error'));
  };
  const cleanup = () => {
    document.removeEventListener('documents-folder-picker-selected', onSelected);
    document.removeEventListener('documents-folder-picker-cancelled', onCancelled);
  };

  // one-shot: a single pick answers a single save request, then the listeners go away
  document.addEventListener('documents-folder-picker-selected', onSelected);
  document.addEventListener('documents-folder-picker-cancelled', onCancelled);

  document.dispatchEvent(new CustomEvent('open-documents-folder-picker', {
    detail: {
      // no title on purpose: the picker localises the personal drive's display name
      defaultDrive: { name: PERSONAL_DRIVE_NAME },
      // The node name, not the title: the picker navigates by JCR node path and the
      // upload uses the picked path verbatim, so starting from the capitalised title
      // would make a second 'Mail Attachments' node next to the lower-cased one —
      // see EXO-88779.
      defaultFolder: ATTACHMENTS_FOLDER_PATH,
      workspace: DEFAULT_WORKSPACE,
    },
  }));
}

/**
 * Opens the composer on a new email carrying this attachment alone. It travels as a
 * download URL rather than as bytes: the composer already knows how to turn a URL
 * into a commons upload id, which is what the backend attaches to the message.
 *
 * @param {Object} attachment the received attachment to forward
 * @returns {void}
 */
export function forwardAttachment(attachment) {
  const mimeType = normaliseMimeType(attachment);
  document.dispatchEvent(new CustomEvent('open-email-compose-with-attachment', {
    detail: {
      attachment: {
        id: null,
        name: attachment.name,
        title: attachment.name,
        mimeType,
        mimetype: mimeType,
        size: attachment.size || 0,
        downloadUrl: getAttachmentUrl(attachment),
      },
    },
  }));
}

/**
 * Whether a received attachment is a contact card. Both signals count, either
 * alone sufficing: senders name the file .vcf with any content type their
 * client felt like, and webmails send text/vcard under generated names.
 * text/x-vcard is the pre-registration spelling old Outlook still uses.
 *
 * @param {Object} attachment the received attachment
 * @returns {Boolean} true when "Add to contacts" applies
 */
export function isVCardAttachment(attachment) {
  return (attachment?.name || '').toLowerCase().endsWith('.vcf')
    || ['text/vcard', 'text/x-vcard'].includes(normaliseMimeType(attachment));
}

/**
 * Reads the first vCard of a received attachment server-side and opens the
 * contact form prefilled with it — the same confirmation step the "add this
 * sender" flow takes, so what is kept is what the user saw and approved, and
 * the actual create keeps the ordinary path's validation and duplicate rule.
 * The server reads the FIRST card only: an emailed contact is one person, and
 * a multi-card file belongs to the contacts import.
 *
 * Errors are reported here as their own toasts — an unreadable card deserves a
 * better sentence than a generic failure — so the returned promise resolves
 * either way and the menu's fallback reporting stays quiet.
 *
 * @param {Object} attachment the received .vcf attachment
 * @param {Object} messages the translated toasts: { notVCard, error }
 * @returns {Promise} resolved once the form was asked to open, or the toast shown
 */
export function addAttachmentToContacts(attachment, messages = {}) {
  const params = new URLSearchParams({
    mailRemoteId: attachment.mailRemoteId,
    attachmentId: attachment.attachmentRemoteId,
  });
  return fetch(`/email-connector/rest/contacts/from-attachment?${params}`, {
    credentials: 'include',
    method: 'GET',
  }).then(resp => {
    if (resp?.ok) {
      return resp.json();
    }
    const status = resp?.status;
    return resp.text().then(code => {
      const error = new Error(code || 'Error when reading the attachment contact');
      error.status = status;
      throw error;
    });
  }).then(prefill =>
    // The listener lives in a QuickActionsGrp module a page may have defined
    // without executing — require it first, or the first click's event lands
    // on nobody (the favorites item and the profile action learned the same).
    new Promise(resolve => window.require(['SHARED/emailConnectorContactsQuickActionExtension'], () => {
      document.dispatchEvent(new CustomEvent('open-contacts-drawer', {detail: {prefill}}));
      resolve();
    }))
  ).catch(error => {
    // A 400 is the server saying "this is not a readable contact card" (or too
    // big a file wearing the name) — the one outcome worth its own sentence.
    notify(error?.status === 400 && messages.notVCard || messages.error, 'error');
  });
}
