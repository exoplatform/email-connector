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

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailAttachment {

  private Long   id;

  private Long   mailRemoteId;

  private String attachmentRemoteId;

  private String name;

  private String mimeType;

  private byte[] data;

  // The folder its message is cached in (see MailFolder). Declared last so the Lombok
  // all-args constructor only grows a trailing argument.
  //
  // It rides on the attachment rather than being plumbed from the message because
  // mailRemoteId alone does not identify anything: IMAP UIDs are PER FOLDER, so
  // (uid, part path) names one row in INBOX and a different one in SENT. Every
  // consumer that addresses an attachment - the download REST call, the front end's
  // getAttachmentUrl, the vCard prefill - therefore needs the folder, and there are
  // more of them than there are places that hold the message. Same reasoning the
  // draft read follows: a fact the row cannot be used without belongs on the row.
  private String folder;

  // The platform FileService id holding the bytes of a file attached to a DRAFT. Null
  // on every attachment that is a part of a message on the server, whose bytes come
  // from the server on demand.
  //
  // Backend-only: the client addresses a draft's attachment by this DTO's own id,
  // under its draft, and has no use for a file-store id. Handing one out would be an
  // invitation to build a second, unowned way to read a file.
  @JsonIgnore
  private Long   fileId;

  // The size in bytes, denormalised from the file when it was stored. Exposed: the
  // composer renders it beside every chip.
  private Long   size;
}
