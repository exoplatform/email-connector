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
 * Published when an administrator moves a connector to another credentials provider.
 * Every user of that connector is then disconnected: the authentication
 * changed for all of them, and a connection left in place would fail at every sync for
 * the users the new provider does not know.
 */
public class EmailConnectorProviderChangedEvent {

  private final long emailConnectorId;

  public EmailConnectorProviderChangedEvent(long emailConnectorId) {
    this.emailConnectorId = emailConnectorId;
  }

  public long getEmailConnectorId() {
    return emailConnectorId;
  }
}
