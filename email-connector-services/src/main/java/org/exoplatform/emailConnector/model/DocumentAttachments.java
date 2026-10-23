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

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What came of attaching documents to a draft: the draft as it now stands, and the
 * documents that were not attached. A refused document does not stop the others: the
 * draft carries every file that could be attached, and the refusals say which ones it
 * does not carry.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentAttachments {

  /** The draft, attachments included, as every other draft call answers with it. */
  private Email                 draft;

  /** The documents that were not attached, in the order they were named; never null. */
  private List<RefusedDocument> refused;
}
