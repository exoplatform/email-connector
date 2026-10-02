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
package org.exoplatform.emailConnector.service;

import java.util.List;
import java.util.Map;

import org.exoplatform.emailConnector.model.Email;

/**
 * Reads the {@code Authentication-Results} header of cached messages from the mail
 * server (EXO-90909), for {@link EmailDmarcVerdictBackfillService} to fill in a verdict
 * the rows were cached without. The mailbox and the folder are the reader's own,
 * bound by whoever hands it over.
 */
@FunctionalInterface
public interface AuthenticationResultsReader {

  /**
   * The header's values of each row's message, by UID, top first; an empty array for a
   * message carrying none. A row whose message the server no longer holds under its
   * UID, or holds another message under, is left out.
   *
   * @param rows the cached rows of one folder: id, UID, Message-ID
   * @return the header's values by UID
   * @throws Exception when the mail server could not be read: nothing is to be recorded
   */
  Map<Long, String[]> read(List<Email> rows) throws Exception; // NOSONAR any failure means "nothing read"
}
