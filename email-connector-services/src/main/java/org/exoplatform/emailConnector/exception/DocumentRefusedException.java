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
package org.exoplatform.emailConnector.exception;

import org.exoplatform.emailConnector.constant.DocumentRefusalReason;

import lombok.Getter;

/**
 * A document that cannot be attached to a draft, with the reason and, when it was
 * resolved before the refusal, the name the user knows it by.
 */
@Getter
public class DocumentRefusedException extends Exception {

  /** The serialization version. */
  private static final long           serialVersionUID = 4519833104722418761L;

  /** Why the document is not attached. */
  private final DocumentRefusalReason reason;

  /** The document's name, or null when it was not resolved. */
  private final String                documentName;

  /**
   * A refusal.
   *
   * @param reason why the document is not attached
   * @param documentName the document's name, or null when it was not resolved
   */
  public DocumentRefusedException(DocumentRefusalReason reason, String documentName) {
    super(reason.name());
    this.reason = reason;
    this.documentName = documentName;
  }
}
