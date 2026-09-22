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
 * The unit a mail server grants access on -- one of the divergences a user sees
 * between servers (delegation plan, sections 3.4 and 13.F). IMAP RFC 4314 sets an ACL
 * per <b>folder</b> (Stalwart's grant was on INBOX specifically); BlueMind's REST
 * {@code _acls} call sets rights on the <b>whole mailbox</b>. Phase 1 grants at
 * mailbox level on every engine -- INBOX on a per-folder server -- and per-folder
 * rights editing is a later phase, on per-folder engines only.
 */
public enum GrantGranularity {

  /** One ACL per folder; a grant on INBOX says nothing about Sent or Trash. */
  FOLDER,

  /** One ACL for the mailbox; every folder follows. */
  MAILBOX,

  /** Nothing can be granted on this server. */
  NONE
}
