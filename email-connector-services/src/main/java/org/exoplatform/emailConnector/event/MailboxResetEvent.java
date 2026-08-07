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

/**
 * Raised when a user resets and re-synchronizes their mailbox, throwing away the
 * cached inbox and reading it again.
 */
public class MailboxResetEvent {

  private final String username;

  /**
   * @param username the mailbox owner
   */
  public MailboxResetEvent(String username) {
    this.username = username;
  }

  /**
   * @return the mailbox owner
   */
  public String getUsername() {
    return username;
  }
}
