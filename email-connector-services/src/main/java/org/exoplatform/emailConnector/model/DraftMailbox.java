/**
 * Copyright (C) 2026 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.model;

/**
 * The mailbox shared with a user that one of their drafts, or scheduled mails, was
 * written in (EXO-90595), as the "Scheduled" view names it: whose mailbox it is, and
 * whether it is still shared with the writer -- a mail written in a mailbox that is no
 * longer shared cannot be sent.
 *
 * @param delegationId the share
 * @param ownerFullName the owner's display name, the mailbox address when no eXo user is
 *          known
 * @param ownerMailbox the owner's mailbox address
 * @param shared whether the share is still accepted
 */
public record DraftMailbox(long delegationId, String ownerFullName, String ownerMailbox, boolean shared) {
}
