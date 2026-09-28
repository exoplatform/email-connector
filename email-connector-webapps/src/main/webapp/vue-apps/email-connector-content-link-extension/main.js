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

/*
 * A mail chip in a note, a task or an activity opens that mail in the mailbox
 * drawer, over the page it is on: the reader stays on the note.
 *
 * The chip is Social's: a drawer extension's chip asks Social's content-link app,
 * which dispatches content-link-email-drawer with the chip's object id -- the
 * cached mail's technical id. That app discovers the listeners by module NAME
 * (includeExtensions('ContentLinkExtension')) before it dispatches, so this module
 * is executed exactly when a chip is clicked or the "/" menu opens, and no sooner.
 */

const EVENT_NAME = 'content-link-email-drawer';

const MAILBOX_BUNDLE = 'locale.portlet.emailConnector.emailConnectorMailBox';

const PRIVATE_MAIL_KEY = 'emailConnector.contentLink.privateMail';

const OPENABLE_FOLDER = /^(INBOX|SENT|ARCHIVE|ALL_MAIL|TRASH|JUNK|CUSTOM:\d{1,18})$/;

let listening = false;

/**
 * Listens to the clicks on mail chips, once per page.
 *
 * @returns {void}
 */
export function init() {
  if (!listening) {
    listening = true;
    document.addEventListener(EVENT_NAME, event => openMail(event?.detail));
  }
}

/**
 * Opens the caller's own mail a chip designates, or says it is private.
 *
 * The id is resolved through the read the Favorites drawer uses: it answers the
 * caller's own mail only, and 404 alike for somebody else's mail and for no mail
 * at all, so nothing here can tell the two apart either.
 *
 * @param {string} emailId the chip's object id, the cached mail's technical id
 * @returns {Promise<void>} resolves once the mailbox was asked to open, or the
 *          alert shown
 */
async function openMail(emailId) {
  if (!/^\d{1,18}$/.test(String(emailId || ''))) {
    showPrivateMailAlert();
    return;
  }
  let email = null;
  try {
    const response = await fetch(`/email-connector/rest/email-box/favorites/${emailId}`, {
      method: 'GET',
      credentials: 'include',
    });
    email = response.ok ? await response.json() : null;
  } catch (e) {
    email = null;
  }
  if (!email) {
    showPrivateMailAlert();
    return;
  }
  window.require(['SHARED/emailConnectorQuickActionExtension'], () =>
    document.dispatchEvent(new CustomEvent('open-email-box-mail', {detail: openingOf(email)})));
}

/**
 * What the mailbox is asked to open for a mail: that mail in its reader when it
 * can be named from outside -- by its IMAP UID in a folder the reader opens from
 * outside -- else the mailbox alone, as the mail's permanent link does (a draft
 * has no UID to be named by).
 *
 * @param {Object} email the caller's own mail
 * @returns {Object} {mailRemoteId, folder}, or {} for the mailbox
 */
function openingOf(email) {
  const folder = email.folder || 'INBOX';
  if (!email.mailRemoteId || !OPENABLE_FOLDER.test(folder)) {
    return {};
  }
  return {mailRemoteId: email.mailRemoteId, folder};
}

/**
 * The platform's alert, saying the mail cannot be opened: the same words for a
 * mail of somebody else and for one that is gone.
 *
 * @returns {void}
 */
function showPrivateMailAlert() {
  const lang = window.eXo?.env?.portal?.language || 'en';
  const url = `/email-connector/i18n/${MAILBOX_BUNDLE}?lang=${lang}`;
  window.require(['SHARED/eXoVueI18n'], exoi18n => exoi18n.loadLanguageAsync(lang, url)
    .then(i18n => document.dispatchEvent(new CustomEvent('alert-message', {
      detail: {
        alertType: 'warning',
        alertMessage: i18n.t(PRIVATE_MAIL_KEY),
      },
    }))));
}
