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
import { sharedMailboxOfFolder } from './EmailConnectorSharedMailboxes.js';

/**
 * Whether a mail is one of the user's own mailbox: filters and their Automations
 * panel apply there only (EXO-90654). A row of a mailbox somebody shared with the user
 * is someone else's mail.
 *
 * @param {object} email - the mail, as the mailbox lists it
 * @returns {boolean} true for a mail of the user's own mailbox
 */
export function isOwnMailboxMail(email) {
  return !!email && !sharedMailboxOfFolder(email.folder || 'INBOX');
}

/**
 * Whether "Create a filter from this mail" is offered on a mail: a received mail of
 * the user's own mailbox with a sender, never a draft.
 *
 * @param {object} email - the mail
 * @returns {boolean} true when offered
 */
export function canCreateFilterFrom(email) {
  return isOwnMailboxMail(email) && !email.draftLocalId && !!email.sender?.address
    && !['DRAFTS', 'SENT', 'SCHEDULED'].includes(email.folder);
}
