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

import org.exoplatform.emailConnector.constant.DocumentRefusalReason;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A document named for a draft and not attached to it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefusedDocument {

  /** The document id as it was given. */
  private String                documentId;

  /** The document's name, when it was resolved before the refusal; null otherwise. */
  private String                name;

  /** Why it was not attached. */
  private DocumentRefusalReason reason;
}
