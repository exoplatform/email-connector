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
package org.exoplatform.emailConnector.provider;

import org.exoplatform.emailConnector.exception.DocumentRefusedException;
import org.exoplatform.emailConnector.model.EmailDocumentContent;

/**
 * Reads a document of the platform's Documents as a given user, to attach it to that
 * user's draft. Implemented over the Documents add-on when it is deployed
 * ({@link DocumentsEmailDocumentReader}); without it no implementation exists, and the
 * mailbox service refuses every document as unavailable.
 */
public interface EmailDocumentReader {

  /**
   * Reads a document with the user's own rights: a document the user cannot open in
   * Documents is refused here too.
   *
   * @param documentId the document's id, as Documents gives it
   * @param username the user reading it
   * @param maxSize the most bytes the document may weigh: a larger one is refused
   *          before its content is held in memory whenever its size is known
   * @return the document's name, content type and content
   * @throws DocumentRefusedException when the document is not found, not open to the
   *           user, a folder, larger than {@code maxSize}, or unreadable
   */
  EmailDocumentContent readDocument(String documentId, String username, long maxSize) throws DocumentRefusedException;
}
