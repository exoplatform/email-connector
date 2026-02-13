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

import java.util.Date;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "EmailBoxEntity")
@Table(name = "EMAIL_BOX")
public class EmailBoxEntity {

  @Id
  @SequenceGenerator(name = "SEQ_EMAIL_ID", sequenceName = "SEQ_EMAIL_ID", allocationSize = 1)
  @GeneratedValue(strategy = GenerationType.AUTO, generator = "SEQ_EMAIL_ID")
  @Column(name = "ID")
  private Long                        id;

  @Column(name = "MAIL_REMOTE_ID")
  private Long                        mailRemoteId;

  @Column(name = "MAIL_HEADER_ID")
  private String                      mailHeaderId;

  @Column(name = "USER_ID")
  private String                      userId;

  @Column(name = "SUBJECT")
  private String                      subject;

  @Column(name = "BODY")
  private String                      body;

  @Column(name = "SENDER")
  private String                      sender;

  @Column(name = "RECEIVER")
  private String                      to;

  @Column(name = "CC")
  private String                      cc;

  @Column(name = "BCC")
  private String                      bcc;

  @Column(name = "RECEIVED_DATE")
  private Date                        receivedDate;

  @Column(name = "IS_READ")
  private boolean                     read;

  @OneToMany(mappedBy = "email", cascade = CascadeType.PERSIST)
  private List<EmailAttachmentEntity> attachments;
}
