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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
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
  @SequenceGenerator(name = "SEQ_EMAIL_ATTACHMENT_ID", sequenceName = "SEQ_EMAIL_ATTACHMENT_ID", allocationSize = 1)
  @GeneratedValue(strategy = GenerationType.AUTO, generator = "SEQ_EMAIL_ATTACHMENT_ID")
  @Column(name = "ID")
  private Long           id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "MAIL_ID")
  private EmailBoxEntity email;

  @Column(name = "ATTACHMENT_REMOTE_ID")
  private String         attachmentRemoteId;

  @Column(name = "NAME")
  private String         name;

  @Column(name = "MIME_TYPE")
  private String         mimeType;
}
