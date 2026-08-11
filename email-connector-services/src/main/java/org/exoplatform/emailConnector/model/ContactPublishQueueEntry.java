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

/**
 * One publish the address book has not taken yet.
 * <p>
 * Deliberately only the contact's id, never a copy of its fields: the contact
 * itself stays in the store, visible and editable, and the publish that finally
 * goes out reads it fresh — so an entry can never publish a stale copy of a
 * contact the user has since corrected, and losing the queue can never lose a
 * contact, only the reminder to push it.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ContactPublishQueueEntry {

  /** The contact to publish, resolved fresh at every attempt. */
  private long    contactId;

  /** When the failed publish was put aside, for display and for honesty. */
  private Long    enqueuedDate;

  /**
   * How many drains have tried this entry — the click that queued it does not
   * count, it already had its own attempt.
   */
  private int     attempts;

  /**
   * Whether the queue has given up retrying by itself. A parked entry is not a
   * lost one: the contact is still local and its publish action still works,
   * which is the retry that un-parks it.
   */
  private boolean parked;

  /** Why the entry was parked, as a message code or the failure's own words. */
  private String  parkedReason;

  /** What the last attempt failed with, kept even before parking. */
  private String  lastError;

  /** When the last attempt ran, so a stuck entry can be told from a fresh one. */
  private Long    lastAttemptDate;
}
