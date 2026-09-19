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

// EXO-90434 -- scheduled send: the REST calls of the composer's "Schedule send" and of
// the "Scheduled" view, and the few pure helpers both read (the date a mail goes at,
// what its state says). Re-exported by EmailConnectorMailBoxService, so every
// component reaches them as this.$emailConnectorMailBoxService.<name>.

const SCHEDULED_REST = '/email-connector/rest/email-box/scheduled';

/** The earliest a mail may be scheduled, from now: the server refuses anything sooner. */
export const MIN_SCHEDULE_DELAY_MS = 60 * 1000;

/** The latest a mail may be scheduled, from now: a year, as the server bounds it. */
export const MAX_SCHEDULE_HORIZON_MS = 365 * 24 * 60 * 60 * 1000;

/**
 * Each action's icon and label key: the menus of a Scheduled view's row and of the mail
 * opened from it show the ones the mail's state offers (scheduledActions), in that order.
 */
export const SCHEDULED_ACTIONS = {
  edit: { icon: 'fa-pen', label: 'emailConnector.mailBox.scheduled.action.edit' },
  reschedule: { icon: 'fa-calendar-alt', label: 'emailConnector.mailBox.scheduled.action.reschedule' },
  sendNow: { icon: 'fa-paper-plane', label: 'emailConnector.mailBox.scheduled.action.sendNow' },
  retry: { icon: 'fa-redo', label: 'emailConnector.mailBox.scheduled.action.retry' },
  sendAgain: { icon: 'fa-redo', label: 'emailConnector.mailBox.scheduled.action.sendAgain' },
  cancel: { icon: 'fa-ban', label: 'emailConnector.mailBox.scheduled.action.cancel' },
  moveToDrafts: { icon: 'fa-file-alt', label: 'emailConnector.mailBox.scheduled.action.moveToDrafts' },
  discard: { icon: 'fa-trash', label: 'emailConnector.mailBox.scheduled.action.discard' },
};

/** How many scheduled mails the view reads per page. */
export const SCHEDULED_PAGE_SIZE = 20;

/**
 * The reasons a mail may not have gone out that mean "not sent": the ones the view
 * says as "Not sent: {reason}". The two others (INTERRUPTED, UNCONFIRMED) mean it may
 * have gone, and are said as "Couldn't confirm it was sent".
 */
export const NOT_SENT_REASONS = ['NETWORK', 'RECIPIENT_REFUSED', 'AUTHENTICATION', 'ATTACHMENT_GONE', 'TOO_LARGE',
  'DISCONNECTED', 'REFUSED', 'INTERNAL'];

/**
 * Turns a refused response into an Error carrying the server's message code, when it
 * sent one: Spring answers a ResponseStatusException with a JSON body whose message is
 * the code, and some paths answer the code as plain text. Both are read, so the screen
 * can say why in the user's words; the HTTP status travels too, for the 409s.
 *
 * @param {Response} resp the refused response
 * @param {String} fallback the message when the body carries no code
 * @returns {Promise} rejecting with the Error, {code, status} set
 */
export function refusal(resp, fallback) {
  const read = resp && typeof resp.text === 'function' ? resp.text().catch(() => '') : Promise.resolve('');
  return read.then(body => {
    let code = null;
    if (body) {
      try {
        const parsed = JSON.parse(body);
        code = typeof parsed?.message === 'string' ? parsed.message : null;
      } catch (e) {
        code = body.trim();
      }
    }
    // Only a message code is a code: an HTML error page or a sentence is not one, and
    // handing it to the bundle would print it raw.
    if (code && !/^[a-zA-Z][\w.-]*$/.test(code)) {
      code = null;
    }
    const error = new Error(code || fallback);
    error.code = code;
    error.status = resp?.status;
    throw error;
  });
}

/**
 * Schedules a draft to be sent at a date (POST /drafts/{id}/schedule). The draft is the
 * composer's text, as the send carries it: the server saves it onto the draft, then
 * freezes it.
 *
 * @param {String} draftLocalId the draft's local id
 * @param {Object} draft the composed draft, as sendDraft sends it
 * @param {Number} scheduledDate the instant, epoch milliseconds
 * @param {String} timeZone the zone it was chosen in
 * @returns {Promise<Object>} the scheduled mail
 */
export function scheduleDraft(draftLocalId, draft, scheduledDate, timeZone) {
  return fetch(`/email-connector/rest/email-box/drafts/${encodeURIComponent(draftLocalId)}/schedule`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST',
    body: JSON.stringify({ draft, scheduledDate, timeZone }),
  }).then(resp => (resp?.ok ? resp.json() : refusal(resp, 'Error when scheduling the email')));
}

/**
 * A page of the user's scheduled mails, soonest first (GET /scheduled).
 *
 * @param {Number} offset the first row, a multiple of limit
 * @param {Number} limit the page size
 * @returns {Promise<Array>} the page
 */
export function getScheduledEmails(offset = 0, limit = SCHEDULED_PAGE_SIZE) {
  const params = new URLSearchParams({ offset, limit });
  return fetch(`${SCHEDULED_REST}?${params}`, {
    credentials: 'include',
    cache: 'no-store',
    method: 'GET'
  }).then(resp => (resp?.ok ? resp.json() : refusal(resp, 'Error when getting the scheduled emails')));
}

/**
 * Gives a scheduled mail, or one that was not sent, a new date (PUT /scheduled/{id}).
 *
 * @param {String} draftLocalId the draft's local id
 * @param {Number} scheduledDate the new instant, epoch milliseconds
 * @param {String} timeZone the zone it was chosen in
 * @returns {Promise<Object>} the scheduled mail
 */
export function rescheduleEmail(draftLocalId, scheduledDate, timeZone) {
  return fetch(`${SCHEDULED_REST}/${encodeURIComponent(draftLocalId)}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT',
    body: JSON.stringify({ scheduledDate, timeZone }),
  }).then(resp => (resp?.ok ? resp.json() : refusal(resp, 'Error when rescheduling the email')));
}

/**
 * Replaces a scheduled mail's content in place, and its date when one is given, in one
 * step the server runs in one transaction (PUT /scheduled/{id}/content): the mail stays
 * scheduled (EXO-90434).
 *
 * @param {String} draftLocalId the draft's local id
 * @param {Object} draft the edited mail: recipients, subject, body, new files as uploads
 * @param {Array<Number>} removedAttachmentIds its stored files to take off
 * @param {Number} scheduledDate a new instant, epoch milliseconds, or null to keep it
 * @param {String} timeZone the zone it was chosen in, with a new instant
 * @returns {Promise<Object>} the scheduled mail
 */
export function updateScheduledEmailContent(draftLocalId, draft, removedAttachmentIds, scheduledDate, timeZone) {
  return fetch(`${SCHEDULED_REST}/${encodeURIComponent(draftLocalId)}/content`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PUT',
    body: JSON.stringify({ draft, removedAttachmentIds: removedAttachmentIds || [], scheduledDate, timeZone }),
  }).then(resp => (resp?.ok ? resp.json() : refusal(resp, 'Error when updating the scheduled email')));
}

/**
 * Cancels a schedule: the mail goes back to Drafts, its content kept
 * (DELETE /scheduled/{id}).
 *
 * @param {String} draftLocalId the draft's local id
 * @returns {Promise<void>} resolved once cancelled
 */
export function cancelScheduledEmail(draftLocalId) {
  return fetch(`${SCHEDULED_REST}/${encodeURIComponent(draftLocalId)}`, {
    credentials: 'include',
    method: 'DELETE'
  }).then(resp => {
    if (!resp?.ok) {
      return refusal(resp, 'Error when cancelling the scheduled email');
    }
  });
}

/**
 * Sends a scheduled mail now, or again (POST /scheduled/{id}/send). Synchronous: the
 * answer comes once the mail server answered, which can take minutes.
 *
 * @param {String} draftLocalId the draft's local id
 * @returns {Promise<Object>} the mail as it now stands, status SENT when it went out
 */
export function sendScheduledEmailNow(draftLocalId) {
  return fetch(`${SCHEDULED_REST}/${encodeURIComponent(draftLocalId)}/send`, {
    credentials: 'include',
    method: 'POST'
  }).then(resp => (resp?.ok ? resp.json() : refusal(resp, 'Error when sending the scheduled email')));
}

/**
 * The time zone of this browser, which a mail scheduled from it is scheduled in.
 *
 * @returns {String} the IANA zone, or null when the browser does not say
 */
export function browserTimeZone() {
  try {
    return new Intl.DateTimeFormat().resolvedOptions().timeZone || null;
  } catch (e) {
    return null;
  }
}

/**
 * The date and time a mail goes at, in the user's language, in the zone it was chosen
 * in when that zone is known (so the hour shown is the hour picked), else in this
 * browser's.
 *
 * @param {Number} scheduledDate the instant, epoch milliseconds
 * @param {String} timeZone the zone it was chosen in, may be null
 * @returns {String} the formatted date and time
 */
export function formatScheduledDate(scheduledDate, timeZone) {
  const lang = (window.eXo?.env?.portal?.language || 'en').replace('_', '-');
  const options = { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' };
  if (timeZone) {
    try {
      return new Intl.DateTimeFormat(lang, { ...options, timeZone }).format(new Date(scheduledDate));
    } catch (e) {
      // An unknown zone: the browser's.
    }
  }
  return new Intl.DateTimeFormat(lang, options).format(new Date(scheduledDate));
}

/**
 * The time alone, in the user's language and in this browser's zone: what the picker's
 * "Sent at {time} ({zone})" caption says.
 *
 * @param {Number} scheduledDate the instant, epoch milliseconds
 * @returns {String} the formatted time
 */
export function formatScheduledTime(scheduledDate) {
  const lang = (window.eXo?.env?.portal?.language || 'en').replace('_', '-');
  return new Intl.DateTimeFormat(lang, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
    .format(new Date(scheduledDate));
}

/**
 * What a scheduled mail's state says in the view, as an i18n key and its colour:
 * nothing while it waits, "Sending..." while it goes, "Not sent: {reason}" in red, or
 * "Couldn't confirm it was sent" in orange.
 *
 * @param {Object} scheduled the scheduled mail ({status, lastError})
 * @returns {Object} {key, reasonKey, color}, or null when there is nothing to say
 */
export function scheduledStateLine(scheduled) {
  const status = scheduled?.status;
  if (status === 'SENDING') {
    return { key: 'emailConnector.mailBox.scheduled.sending', color: 'primary--text' };
  }
  if (status === 'UNCERTAIN') {
    return { key: 'emailConnector.mailBox.scheduled.uncertain', color: 'warning--text' };
  }
  if (status === 'FAILED') {
    const reason = NOT_SENT_REASONS.includes(scheduled.lastError) ? scheduled.lastError : 'unknown';
    return {
      key: 'emailConnector.mailBox.scheduled.notSent',
      reasonKey: `emailConnector.mailBox.scheduled.error.${reason}`,
      color: 'error--text',
    };
  }
  return null;
}

/**
 * The actions a scheduled mail offers in its state, in menu order. A mail being sent
 * offers none; one whose sending could not be confirmed may not be rescheduled (the
 * server refuses it) and is sent again only on purpose; one that failed is retried.
 *
 * @param {Object} scheduled the scheduled mail ({status})
 * @returns {Array<String>} the action names
 */
export function scheduledActions(scheduled) {
  switch (scheduled?.status) {
  case 'SENDING':
  case 'SENT':
    return [];
  case 'FAILED':
    return ['retry', 'edit', 'reschedule', 'moveToDrafts', 'discard'];
  case 'UNCERTAIN':
    return ['sendAgain', 'edit', 'moveToDrafts', 'discard'];
  default:
    return ['edit', 'reschedule', 'sendNow', 'cancel', 'discard'];
  }
}

/**
 * What to tell the user about a refused scheduled-send request: the server's message
 * code when the bundle translates it (every code the backend answers with has its key),
 * else the given fallback.
 *
 * @param {Error} error the refusal, as refusal() builds it
 * @param {Object} vm the component, for $t and $te
 * @param {String} fallbackKey the key to use when the code is unknown
 * @returns {String} the message
 */
export function scheduledErrorMessage(error, vm, fallbackKey) {
  const code = error?.code;
  if (code && typeof vm.$te === 'function' && vm.$te(code)) {
    return vm.$t(code);
  }
  return vm.$t(fallbackKey);
}

/**
 * Plain text as HTML text: its markup characters escaped.
 *
 * @param {String} text the text, may be null
 * @returns {String} the HTML
 */
function escapeText(text) {
  return String(text || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/**
 * The row the reader is opened on for a mail of the Scheduled view: the draft, shown
 * read-only in its conversation the way a scheduled reply already is (PO decision (a)),
 * built from what GET /scheduled lists. The conversation is read by its thread id when
 * the row carries one; without it the reader shows this row alone, as it does for any
 * message whose conversation reads empty. It keeps the listed row, whose actions the
 * reader then offers (scheduledRow), and is marked read: nothing about opening it may
 * be pushed to the mail server as a read status.
 *
 * @param {Object} scheduled the scheduled mail, as GET /scheduled lists it
 * @returns {Object} the draft row the reader opens on
 */
export function scheduledReaderRow(scheduled) {
  return {
    draftLocalId: scheduled.draftLocalId,
    threadId: scheduled.threadId || null,
    folder: 'DRAFTS',
    subject: scheduled.subject || '',
    to: scheduled.to || [],
    // The snippet is plain text, and a body is read as HTML: escaped, so a "<" in it
    // stays a character.
    content: { body: scheduled.body || escapeText(scheduled.snippet), attachments: scheduled.attachments || [] },
    receivedDate: scheduled.scheduledDate,
    read: true,
    scheduled: true,
    scheduledDate: scheduled.scheduledDate,
    scheduledTimeZone: scheduled.timeZone,
    scheduledStatus: scheduled.status,
    scheduledRow: scheduled,
  };
}
