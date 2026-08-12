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
 * Raised when a user AUTHORED one contact — the create form's own path, named
 * {@code ContactOrigin.USER_FORM} at the service boundary. Never raised by the
 * vCard import, the collection from mail, the backfill, the rebind hand-over or
 * the directory import, however many rows those create.
 * <p>
 * It exists so the contact store can stay unaware of address books. The store
 * says what happened — a person authored this contact — and the CardDAV side
 * decides whether that means anything, which is also what keeps the two
 * services out of a bean cycle: the write-back already depends on the store.
 */
public class ContactAuthoredEvent {

  private final String username;

  private final long   contactId;

  /**
   * @param username the store owner who authored the contact
   * @param contactId the contact as it was stored, revived rows included
   */
  public ContactAuthoredEvent(String username, long contactId) {
    this.username = username;
    this.contactId = contactId;
  }

  /**
   * @return the store owner who authored the contact
   */
  public String getUsername() {
    return username;
  }

  /**
   * @return the stored contact's id
   */
  public long getContactId() {
    return contactId;
  }
}
