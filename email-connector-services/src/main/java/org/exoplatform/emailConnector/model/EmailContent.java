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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailContent {

  private String                body;

  private String                excerpt;

  private List<EmailAttachment> attachments;

  private boolean               html;

  /**
   * Whether the body served to the reader had resources fetched from the internet
   * (images, backgrounds, fonts) taken out until the user agrees to load them
   * (EXO-90841). Set on the reads that feed the reader, never stored.
   */
  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  private boolean               remoteContentBlocked;

  /**
   * Why the message looks suspicious, one entry per rule it trips (EXO-90841). Empty
   * when it trips none. Set on the reads that feed the reader, never stored.
   */
  @JsonProperty(access = JsonProperty.Access.READ_ONLY)
  private List<EmailSecurityWarning> securityWarnings;

  /**
   * The sender-authentication method that failed according to the receiving server's
   * own {@code Authentication-Results} header (DMARC, SPF or DKIM), null when none did
   * or the server did not say. Read at sync, when the header still exists, and stored
   * with the row. Backend-only.
   */
  @JsonIgnore
  private String                authFailure;

  public EmailContent(String body) {
    this.body = body;
  }

  public EmailContent(String body, String excerpt, List<EmailAttachment> attachments) {
    this.body = body;
    this.excerpt = excerpt;
    this.attachments = attachments;
  }
}
