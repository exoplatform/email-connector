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
   * Whether the receiving server's sender check vouches for this message's domain
   * (EXO-90893): it passed DMARC and failed nothing. Read from the stored row, for the
   * mail list, which may show the brand logo the page learnt for the address on this
   * row only; never a reason to trust anything else.
   */
  private boolean domainVerified;

  /**
   * Whether the reader offered no brand logo only because the domain's is still being
   * looked up for the reading user (EXO-90909): the page is told over the WebSocket when
   * it is found, and may then show the logo its avatar cache gets for the address. Set
   * by the reader's decoration only, under the same conditions as {@code logoUrl}.
   */
  private boolean logoPending;

  /**
   * A sender without a brand logo.
   *
   * @param name the name to show
   * @param address the address
   * @param avatarUrl the picture, or null
   * @param profileUrl the platform profile, or null
   */
  public EmailSender(String name, String address, String avatarUrl, String profileUrl) {
    this(name, address, avatarUrl, profileUrl, null, false, false);
  }
}
