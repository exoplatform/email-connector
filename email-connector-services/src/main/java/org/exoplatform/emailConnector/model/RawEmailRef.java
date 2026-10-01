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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One message as the mailbox list names it (EXO-90845): the folder key it is listed in
 * and its IMAP UID there. Nothing else, on purpose: who may read it, and whether the
 * caller has it at all, is decided from the caller's own cache, never from what the
 * reference claims.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RawEmailRef {

  /** The folder key the message is listed in: a built-in key, or {@code CUSTOM:<id>}. */
  private String folder;

  /** The message's IMAP UID in that folder. */
  private long   mailRemoteId;
}
