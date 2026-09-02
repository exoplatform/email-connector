/**
 * Copyright (C) 2025 eXo Platform SAS This program is free software: you can
 * redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. This program is
 * distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details. You
 * should have received a copy of the GNU Affero General Public License along
 * with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.event;

/**
 * A user changed which categories they want to be notified about.
 * <p>
 * Separate from {@link EmailBoxCleanupEvent}: nothing about the mailbox binding
 * moved, only the preference — but that preference is what the Application
 * Center badge counts by ("the messages that would have notified"), so the
 * badge has to be told, and the settings service cannot tell the mail service
 * directly without closing a bean cycle. The event is the edge instead.
 */
public class EmailNotificationPreferencesChangedEvent {

  private final String username;

  /**
   * @param username the user whose notification preference was saved
   */
  public EmailNotificationPreferencesChangedEvent(String username) {
    this.username = username;
  }

  /**
   * @return the user whose notification preference was saved
   */
  public String getUsername() {
    return username;
  }
}
