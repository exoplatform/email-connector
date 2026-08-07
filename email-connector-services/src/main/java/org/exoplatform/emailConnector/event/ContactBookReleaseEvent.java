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
 * Raised when a user's address-book binding changes: the book is switched off, or
 * the mailbox behind it is rebound or disconnected.
 * <p>
 * Separate from {@code EmailBoxCleanupEvent} because switching the address book off
 * must not touch the cached mail, and the two are not always raised together.
 */
public class ContactBookReleaseEvent {

  private final String username;

  /**
   * @param username the mailbox owner whose binding changed
   */
  public ContactBookReleaseEvent(String username) {
    this.username = username;
  }

  /**
   * @return the mailbox owner whose binding changed
   */
  public String getUsername() {
    return username;
  }
}
