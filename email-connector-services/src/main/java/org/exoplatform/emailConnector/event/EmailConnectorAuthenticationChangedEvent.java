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
 * Published when an administrator's save changes how a connector authenticates its
 * users: another credentials provider, or a provider configuration written. The managed
 * refusals recorded on that connector are then forgotten (EXO-91017).
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
