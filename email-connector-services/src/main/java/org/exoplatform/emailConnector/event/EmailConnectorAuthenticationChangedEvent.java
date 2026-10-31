/**
 * Copyright (C) 2026 eXo Platform SAS
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

/**
 * Published when an administrator's save changes how a connector reaches its users'
 * mailboxes: another credentials provider, a provider configuration value that changes,
 * a secret typed again, the IMAP or SMTP host or port, or the SMTP security type. An
 * edit of the name, the icon, the webmail address or the translations publishes
 * nothing. The managed refusals recorded on that connector are then forgotten
 * (EXO-91017, EXO-92306).
 */
public class EmailConnectorAuthenticationChangedEvent {

  private final long emailConnectorId;

  public EmailConnectorAuthenticationChangedEvent(long emailConnectorId) {
    this.emailConnectorId = emailConnectorId;
  }

  public long getEmailConnectorId() {
    return emailConnectorId;
  }
}
