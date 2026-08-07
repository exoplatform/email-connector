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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "EmailConnectorEntity")
@Table(name = "EMAIL_CONNECTOR")
public class EmailConnectorEntity {

  @Id
  @SequenceGenerator(name = "SEQ_EMAIL_CONNECTOR_ID", sequenceName = "SEQ_EMAIL_CONNECTOR_ID", allocationSize = 1)
  @GeneratedValue(strategy = GenerationType.AUTO, generator = "SEQ_EMAIL_CONNECTOR_ID")
  @Column(name = "ID")
  private Long    id;

  @Column(name = "NAME")
  private String  name;

  @Column(name = "IMAGE_FILE_ID")
  private Long    imageFileId;

  @Column(name = "ICON")
  private String  icon;

  @Column(name = "IMAP_URL")
  private String  imapUrl;

  @Column(name = "IMAP_PORT")
  private String  imapPort;

  @Column(name = "SMTP_URL")
  private String  smtpUrl;

  @Column(name = "SMTP_PORT")
  private String  smtpPort;

  @Column(name = "SMTP_SECURITY_TYPE")
  private String  smtpSecurityType;

  @Column(name = "ACTIVE")
  private boolean active;

  @Column(name = "WEB_MAIL_URL")
  private String  webmailUrl;

  /**
   * Where this provider's address book lives, so contacts can be read over
   * CardDAV. Null when the provider offers none, which is the honest state for
   * Microsoft 365 and Proton and the default for every connector until an
   * administrator fills it in.
   */
  @Column(name = "CARDDAV_URL")
  private String  carddavUrl;
}
