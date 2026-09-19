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

// EXO-90435 -- read receipts (RFC 8098), the reader's half: the answer to a message
// that asks to be notified when it is read, and what the reader says about the outcome.
// Re-exported by EmailConnectorMailBoxService, so every component reaches them as
// this.$emailConnectorMailBoxService.<name>.

import { refusal } from './EmailConnectorScheduledSendService.js';

/** The reader answers with a receipt. */
export const READ_RECEIPT_SEND = 'SEND';

/** The reader answers without one. Final, as a receipt is. */
export const READ_RECEIPT_IGNORE = 'IGNORE';

// The server's refusal of an automatic answer it would not give on its own right now:
// the reader shows the banner instead.
const ASK_FIRST = 'emailConnector.readReceipt.askFirst';

// The server's codes, each with the key of the sentence the reader shows for it.
const OUTCOME_MESSAGES = {
  'emailConnector.readReceipt.sendFailed': 'emailConnector.mailBox.readReceipt.sendFailed',
  'emailConnector.readReceipt.unconfirmed': 'emailConnector.mailBox.readReceipt.unconfirmed',
  'emailConnector.readReceipt.notAllowed': 'emailConnector.mailBox.readReceipt.notAllowed',
  'emailConnector.readReceipt.notRequested': 'emailConnector.mailBox.readReceipt.notRequested',
};

// The messages this page already answered on its own (AUTO), by technical id: an
// automatic receipt is posted once per message and page, however many times the
// message is rendered again -- collapsed and expanded, re-read, or served from a
// cached response whose prompt is stale (the GET's ETag does not cover it).
const answeredAutomatically = new Set();

/**
 * Answers a message's read-receipt request (POST /email-box/{id}/read-receipt).
 *
 * @param {Number} emailId the message's technical id
 * @param {String} action READ_RECEIPT_SEND or READ_RECEIPT_IGNORE
 * @param {Boolean} automatic whether the reader answers on its own, on display, rather
 *          than on a click: only for a SEND, and only while the prompt is AUTO
 * @returns {Promise<void>} resolved once answered; rejected with {code, status}
 */
export function respondToReadReceipt(emailId, action, automatic = false) {
  return fetch(`/email-connector/rest/email-box/${encodeURIComponent(emailId)}/read-receipt`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST',
    body: JSON.stringify({ action, automatic: !!automatic }),
  }).then(resp => (resp?.ok ? null : refusal(resp, 'Error when answering the read receipt request')));
}

/**
 * Sends the automatic receipt of a message the user has on screen, once per message
 * and page. The caller decides when the message counts as displayed (see the banner).
 *
 * @param {Object} email the message, its prompt AUTO
 * @returns {Promise<void>|null} the answer, or null when it was already posted
 */
export function answerReadReceiptAutomatically(email) {
  if (!email?.id || answeredAutomatically.has(email.id)) {
    return null;
  }
  answeredAutomatically.add(email.id);
  return respondToReadReceipt(email.id, READ_RECEIPT_SEND, true);
}

/**
 * What the reader does with a refused answer: {prompt, messageKey, alertType}.
 * <ul>
 * <li>409: the request was answered elsewhere; the banner goes, silently.</li>
 * <li>404: the message is gone (moved or deleted since it was shown); nothing is left
 *   to answer here, and the banner goes, silently.</li>
 * <li>400 askFirst: the server will not answer on its own; the banner shows.</li>
 * <li>400 notAllowed / notRequested: nothing to answer; the banner goes, with a word.</li>
 * <li>500 unconfirmed: the receipt may be out and is not sent again; the banner goes.</li>
 * <li>Anything else (500 sendFailed, 401, the network): nothing left; the request stays
 *   pending and the banner stays, so the user can try again.</li>
 * </ul>
 *
 * @param {Error} error the refusal, {code, status}
 * @param {String} currentPrompt the prompt the message had
 * @returns {Object} {prompt, messageKey, alertType}: messageKey null when nothing is said
 */
export function readReceiptOutcome(error, currentPrompt) {
  const code = error?.code;
  if (error?.status === 409 || error?.status === 404) {
    return { prompt: 'NONE', messageKey: null, alertType: null };
  }
  if (code === ASK_FIRST) {
    return { prompt: 'ASK', messageKey: null, alertType: null };
  }
  if (code === 'emailConnector.readReceipt.notAllowed' || code === 'emailConnector.readReceipt.notRequested') {
    return { prompt: 'NONE', messageKey: OUTCOME_MESSAGES[code], alertType: 'info' };
  }
  if (code === 'emailConnector.readReceipt.unconfirmed') {
    return { prompt: 'NONE', messageKey: OUTCOME_MESSAGES[code], alertType: 'warning' };
  }
  return {
    prompt: currentPrompt === 'AUTO' ? 'ASK' : currentPrompt,
    messageKey: OUTCOME_MESSAGES['emailConnector.readReceipt.sendFailed'],
    alertType: 'error',
  };
}
