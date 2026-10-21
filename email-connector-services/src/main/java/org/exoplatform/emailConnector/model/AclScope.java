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
 * Where one access-control entry stands, on a server that grants per folder
 * ({@link GrantGranularity#FOLDER}): on the folder it was read on, or on the owner's
 * whole mailbox. RFC 4314 knows only the first. BlueMind keeps both at once: a
 * whole-mailbox share in the mailbox's own list, and per-folder shares in each folder's
 * list. A folder entry adds to a whole-mailbox entry and never narrows it, so an entry
 * standing on the whole mailbox is changed or removed only at that scope
 * ({@code MailboxAclEngine#grantWholeMailbox}, {@code MailboxAclEngine#revokeWholeMailbox}),
 * never by a write on one folder.
 */
public enum AclScope {

  /** The entry stands on the folder it was read on, and on that folder only. */
  FOLDER,

  /** The entry stands on the owner's whole mailbox: every folder follows it. */
  MAILBOX
}
