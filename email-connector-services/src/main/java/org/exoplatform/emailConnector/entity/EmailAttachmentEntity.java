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
package org.exoplatform.emailConnector.entity;

import io.meeds.common.persistence.PortableSequence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "EmailAttachmentEntity")
@Table(name = "EMAIL_ATTACHMENTS")
public class EmailAttachmentEntity {

  @Id
  @PortableSequence(name = "SEQ_EMAIL_ATTACHMENT_ID")
  @Column(name = "ID")
  private Long           id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "MAIL_ID")
  private EmailBoxEntity email;

  // The MIME part path within the message ("2" being its second part). Null on a file
  // the user attached to a draft: it is not a part of anything until the mail is sent.
  // See changeset 1.0.0-44.
  @Column(name = "ATTACHMENT_REMOTE_ID")
  private String         attachmentRemoteId;

  @Column(name = "NAME")
  private String         name;

  @Column(name = "MIME_TYPE")
  private String         mimeType;

  // The platform FileService id holding the bytes, under the add-on's own namespace.
  // Set only on a draft's own attachment; null on every row mirroring a part of a
  // message on the server, whose bytes are fetched from the server on demand. The two
  // are mutually exclusive by construction and that is the whole distinction between
  // them. Declared after the original columns so the Lombok all-args constructor only
  // grows trailing arguments.
  @Column(name = "FILE_ID")
  private Long           fileId;

  // Denormalised from the file. Read on every list (the composer renders it beside
  // each chip) and on every send (the total is capped), which is a round trip per
  // attachment if it has to be asked of the file service each time.
  @Column(name = "FILE_SIZE")
  private Long           fileSize;
}
