/**
 * Copyright (C) 2025 eXo Platform SAS
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
 * DTO describing an attachment the user adds while composing an outgoing email.
 * Unlike {@link EmailAttachment} (which references a <em>received</em> message
 * part by its MIME path), an outgoing attachment is carried as a commons upload
 * id: the browser uploads every file (device upload or picked platform document)
 * through the commons upload service, and the backend resolves the id to bytes
 * at send time. This keeps the send path free of any ecms/documents dependency.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailOutgoingAttachment {

  private String uploadId;

  private String name;

  private String mimeType;

  private long   size;
}
