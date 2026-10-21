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
package org.exoplatform.emailConnector.constant;

/**
 * Why a document named for a draft was not attached to it. Each reason is told to the
 * caller with the document it concerns, so the one who named it knows which file is
 * missing from the draft, and why.
 */
public enum DocumentRefusalReason {

  /** No document has that id, or it no longer exists. */
  NOT_FOUND,

  /** The document exists, but the user may not open it. */
  NO_ACCESS,

  /** The id names a folder, not a file. */
  NOT_A_FILE,

  /** The file would take the draft over the size a mail may carry. */
  TOO_LARGE,

  /** The file is there and open to the user, but its content could not be read. */
  UNREADABLE,

  /** The Documents add-on is not deployed, so no document can be read. */
  DOCUMENTS_UNAVAILABLE
}
