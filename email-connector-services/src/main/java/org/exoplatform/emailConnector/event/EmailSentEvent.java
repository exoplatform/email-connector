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
package org.exoplatform.emailConnector.event;

import java.util.List;

import org.exoplatform.emailConnector.model.EmailRecipient;

/**
 * Published after an outgoing mail was accepted by the SMTP server, carrying
 * who it was addressed to. Exists because the historical SEND_EMAIL
 * ListenerService broadcast only says {@code "newEmail"|"reply"} — it cannot
 * carry the recipients contact collection feeds on — and its payload is public
 * API to other add-ons now. Deliberately holds To and Cc ONLY: Bcc is never a
 * contact-collection signal.
 */
public class EmailSentEvent {

  private final String               username;

  private final List<EmailRecipient> recipients;

  /**
   * Builds the event.
   *
   * @param username the sender
   * @param recipients the To and Cc recipients of the sent mail
   */
  public EmailSentEvent(String username, List<EmailRecipient> recipients) {
    this.username = username;
    this.recipients = recipients;
  }

  /**
   * The sender.
   *
   * @return the username the mail was sent as
   */
  public String getUsername() {
    return username;
  }

  /**
   * Who the mail was addressed to.
   *
   * @return the To and Cc recipients
   */
  public List<EmailRecipient> getRecipients() {
    return recipients;
  }
}
