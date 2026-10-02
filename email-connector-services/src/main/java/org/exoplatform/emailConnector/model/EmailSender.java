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
public class EmailSender {

  private String name;

  private String address;

  private String avatarUrl;
  
  private String profileUrl;

  /**
   * The sender's brand logo (EXO-90893), served by this add-on, for a sender with no
   * platform profile whose message passed DMARC for its domain and gives no reason for
   * doubt; null otherwise. Set by the reader's decoration only, and shown before
   * {@code avatarUrl}, which stays the fallback when the logo cannot be loaded.
   */
  private String logoUrl;

  /**
   * A sender without a brand logo.
   *
   * @param name the name to show
   * @param address the address
   * @param avatarUrl the picture, or null
   * @param profileUrl the platform profile, or null
   */
  public EmailSender(String name, String address, String avatarUrl, String profileUrl) {
    this(name, address, avatarUrl, profileUrl, null);
  }
}
