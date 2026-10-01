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

// EXO-90840 -- calendar invitations in mail, the reader's half: the event a message's
// iCalendar part describes, the answer to it, and the words the card says them in.
// Re-exported by EmailConnectorMailBoxService, so every component reaches them as
// this.$emailConnectorMailBoxService.<name>.

import { refusal } from './EmailConnectorScheduledSendService.js';

/** The three answers, as the server names them. */
export const INVITATION_ANSWERS = ['ACCEPTED', 'TENTATIVE', 'DECLINED'];

// The server's codes, each with the key of the sentence the reader shows for it.
const OUTCOME_MESSAGES = {
  'emailConnector.invitation.sendFailed': 'emailConnector.mailBox.invitation.sendFailed',
  'emailConnector.invitation.unconfirmed': 'emailConnector.mailBox.invitation.unconfirmed',
  'emailConnector.invitation.cancelled': 'emailConnector.mailBox.invitation.cancelled',
  'emailConnector.invitation.notAnswerable': 'emailConnector.mailBox.invitation.notAnswerable',
  'emailConnector.invitation.sendNotAllowed': 'emailConnector.mailBox.invitation.sendNotAllowed',
  'emailConnector.sendMode.refusedByServer': 'emailConnector.mailBox.invitation.refusedByServer',
};

// The weekday codes of a rule, Monday first: 2024-01-01 was a Monday, which is how a
// code is turned into the weekday's name in the user's language.
const WEEKDAYS = ['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU'];

// The invitations this page already read, by message id: each read is a round trip to
// the mail server, and a message is rendered again whenever it is collapsed and
// expanded.
const invitations = new Map();

/**
 * Whether a message carries an iCalendar part, as the server decides it: a
 * text/calendar or application/ics part, or a .ics file.
 *
 * @param {Object} email the message, with its attachments
 * @returns {Boolean} true when it does
 */
export function hasCalendarPart(email) {
  const attachments = email?.content?.attachments || [];
  return attachments.some(attachment => {
    const type = (attachment?.mimeType || '').toLowerCase();
    return type === 'text/calendar' || type === 'application/ics' || /\.ics$/i.test(attachment?.name || '');
  });
}

/**
 * The invitation a message carries (GET /email-box/{id}/invitation), read once per page.
 *
 * @param {Number} emailId the message's technical id
 * @returns {Promise<Object>} the invitation; rejected with {code, status}
 */
export function getInvitation(emailId) {
  if (!invitations.has(emailId)) {
    const read = fetch(`/email-connector/rest/email-box/${encodeURIComponent(emailId)}/invitation`, {
      credentials: 'include',
      method: 'GET',
    }).then(resp => (resp?.ok ? resp.json() : refusal(resp, 'Error when reading the invitation')));
    // A failure is not kept: the next rendering asks again.
    read.catch(() => invitations.delete(emailId));
    invitations.set(emailId, read);
  }
  return invitations.get(emailId);
}

/**
 * Answers the invitation a message carries (POST /email-box/{id}/invitation/reply).
 *
 * @param {Number} emailId the message's technical id
 * @param {String} answer ACCEPTED, TENTATIVE or DECLINED
 * @returns {Promise<Object>} the invitation with the answer given; rejected with {code, status}
 */
export function replyToInvitation(emailId, answer) {
  return fetch(`/email-connector/rest/email-box/${encodeURIComponent(emailId)}/invitation/reply`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST',
    body: JSON.stringify({ answer }),
  }).then(resp => (resp?.ok ? resp.json() : refusal(resp, 'Error when answering the invitation')))
    .then(invitation => {
      invitations.set(emailId, Promise.resolve(invitation));
      return invitation;
    }, error => {
      // The server may have kept the answer (a doubtful send): the next rendering reads
      // it again rather than the invitation as it was before.
      invitations.delete(emailId);
      throw error;
    });
}

/**
 * What the card says about a refused answer: {messageKey, alertType, answer}. A doubtful
 * send counts as the answer given, as the server keeps it.
 *
 * @param {Error} error the refusal, {code, status}
 * @param {String} answer the answer that was being given
 * @returns {Object} {messageKey, alertType, answer}, answer null unless it stands
 */
export function invitationReplyOutcome(error, answer) {
  const code = error?.code;
  if (code === 'emailConnector.invitation.unconfirmed') {
    return { messageKey: OUTCOME_MESSAGES[code], alertType: 'warning', answer };
  }
  return {
    messageKey: OUTCOME_MESSAGES[code] || OUTCOME_MESSAGES['emailConnector.invitation.sendFailed'],
    alertType: 'error',
    answer: null,
  };
}

/**
 * When an event takes place, in the viewer's own time zone and language: a range of
 * instants for a timed event, of days for an all-day one and of wall-clock times for a
 * floating one, which belong to no zone.
 *
 * @param {Object} invitation the invitation
 * @returns {String} the words, empty when the event says nothing of when
 */
export function formatInvitationWhen(invitation) {
  const lang = (window.eXo?.env?.portal?.language || 'en').replace('_', '-');
  if (invitation?.allDay) {
    const options = { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' };
    const start = localDay(invitation.startDate);
    const end = localDay(invitation.endDate) || start;
    return start ? formatRange(new Intl.DateTimeFormat(lang, options), start, end) : '';
  }
  if (invitation?.floating) {
    // The same wall-clock time wherever it is read: no zone to convert from or to name.
    const start = localDateTime(invitation.startLocal);
    const end = localDateTime(invitation.endLocal) || start;
    const options = { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'UTC' };
    return start ? formatRange(new Intl.DateTimeFormat(lang, options), start, end) : '';
  }
  if (!invitation?.start) {
    return '';
  }
  const options = { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZoneName: 'short' };
  const start = new Date(invitation.start);
  const end = invitation.end ? new Date(invitation.end) : start;
  return formatRange(new Intl.DateTimeFormat(lang, options), start, end);
}

/**
 * A recurring event's rule in words, or the plain "Recurring" when the server could not
 * say it.
 *
 * @param {Object} invitation the invitation
 * @param {Function} t the translator, (key, params) => text
 * @returns {String} the words, empty for an event that does not recur
 */
export function formatInvitationRecurrence(invitation, t) {
  if (!invitation?.recurring) {
    return '';
  }
  const rule = invitation.recurrence;
  if (!rule?.frequency) {
    return t('emailConnector.mailBox.invitation.recurring');
  }
  const lang = (window.eXo?.env?.portal?.language || 'en').replace('_', '-');
  // The count and the end carry their own separator (", 10 times"): appended as they are.
  let words = rule.interval > 1
    ? t(`emailConnector.mailBox.invitation.recurrence.everyN.${rule.frequency}`, { 0: rule.interval })
    : t(`emailConnector.mailBox.invitation.recurrence.every.${rule.frequency}`);
  if (rule.days?.length) {
    const weekday = new Intl.DateTimeFormat(lang, { weekday: 'long' });
    const names = rule.days.filter(day => WEEKDAYS.includes(day)).map(day => weekday.format(new Date(2024, 0, 1 + WEEKDAYS.indexOf(day))));
    words += ` ${t('emailConnector.mailBox.invitation.recurrence.onDays', { 0: names.join(', ') })}`;
  }
  if (rule.monthDays?.length) {
    words += ` ${t('emailConnector.mailBox.invitation.recurrence.onMonthDays', { 0: rule.monthDays.join(', ') })}`;
  }
  if (rule.count) {
    words += t('emailConnector.mailBox.invitation.recurrence.count', { 0: rule.count });
  }
  if (rule.until) {
    const day = localDay(rule.until);
    words += t('emailConnector.mailBox.invitation.recurrence.until', {
      0: day ? new Intl.DateTimeFormat(lang, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' }).format(day) : rule.until,
    });
  }
  return words;
}

/**
 * A day of the calendar as midnight UTC, to be formatted in UTC: that very day whatever
 * the viewer's zone, and never moved by a daylight-saving change.
 *
 * @param {String} isoDate YYYY-MM-DD
 * @returns {Date|null} the day, null when there is none
 */
function localDay(isoDate) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoDate || '');
  return match ? new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]))) : null;
}

/**
 * A wall-clock date-time as that time in UTC, to be formatted in UTC: shown as written in
 * every zone, even at an hour the viewer's daylight-saving change skips.
 *
 * @param {String} isoDateTime YYYY-MM-DDTHH:mm[:ss]
 * @returns {Date|null} the time, null when there is none
 */
function localDateTime(isoDateTime) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(isoDateTime || '');
  return match ? new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]), Number(match[4]), Number(match[5]), Number(match[6] || 0))) : null;
}

/**
 * A range in one format: the browser's own range form, which says a shared day once,
 * else the two ends.
 *
 * @param {Intl.DateTimeFormat} format the format
 * @param {Date} start the start
 * @param {Date} end the end
 * @returns {String} the range
 */
function formatRange(format, start, end) {
  if (end.getTime() === start.getTime()) {
    return format.format(start);
  }
  return typeof format.formatRange === 'function' ? format.formatRange(start, end) : `${format.format(start)} – ${format.format(end)}`;
}
