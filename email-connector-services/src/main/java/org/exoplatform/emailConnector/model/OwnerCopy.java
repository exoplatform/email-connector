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
 * What became of the copy of a shared-mailbox send in its owner's Sent folder
 * (EXO-90551), as the send answers it.
 */
public enum OwnerCopy {
  /** Filed in the owner's Sent. */
  FILED,
  /** The mail went out; the copy could not be filed. */
  FAILED,
  /** Not filed by design: no Sent shared, no i there, or switched off. */
  SKIPPED
}
