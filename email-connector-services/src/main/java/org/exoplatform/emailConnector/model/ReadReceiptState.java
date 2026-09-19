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
 * The answer given to a message's read-receipt request: the local mirror of the
 * IMAP {@code $MDNSent} keyword, which says only "answered" -- this also says how.
 * Kept locally because some servers (Exchange) cannot store keywords, and because
 * the claim that makes a receipt go out at most once is a database write.
 */
public enum ReadReceiptState {
  /** A receipt was sent (or the server says another client answered). */
  SENT,
  /** The user chose not to send one. Final, as SENT is. */
  IGNORED
}
