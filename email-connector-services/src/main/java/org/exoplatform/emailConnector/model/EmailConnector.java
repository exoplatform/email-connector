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

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailConnector {

  private Long    id;

  private String  name;

  private String  imageUrl;

  private Long    imageFileId;

  private String  icon;

  private String  imapUrl;

  private String  imapPort;

  private String  smtpUrl;

  private String  smtpPort;

  private String  smtpSecurityType;

  private boolean active = true;

  private Boolean userConnected;

  private Boolean canConnect;

  private String  imageUploadId;
  
  private String  webmailUrl;

  /**
   * The provider's CardDAV endpoint, or null when it has no address book.
   * <p>
   * Declared LAST on purpose. This class is @AllArgsConstructor and is built
   * positionally in the storage, so a field declared anywhere else would silently
   * shift every argument after it onto the wrong field.
   */
  private String  carddavUrl;

  public EmailConnector(Long id,
                        String name,
                        Long imageFileId,
                        String imapUrl,
                        String imapPort,
                        String smtpUrl,
                        String smtpPort,
                        String smtpSecurityType,
                        boolean active) {
    this.id = id;
    this.name = name;
    this.imageFileId = imageFileId;
    this.imapUrl = imapUrl;
    this.imapPort = imapPort;
    this.active = active;
  }
}
