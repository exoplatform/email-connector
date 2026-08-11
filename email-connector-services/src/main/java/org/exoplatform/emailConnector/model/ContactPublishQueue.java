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

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The publishes a user's address book has not taken yet, waiting for the next
 * run that proves the server reachable.
 * <p>
 * Held as JSON in settings beside {@link ContactSyncState}, under its own key
 * for the same reason that state has its own: documents rewritten whole by
 * different writers must not share one. Not a JPA table on purpose — the queue
 * is short (a handful of clicks that happened to hit an outage), strictly
 * per-user, and read only around a sync run, which is exactly the shape the
 * settings store already holds well; a schema would outlive the outage it
 * describes.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ContactPublishQueue {

  /** The pending and parked entries, oldest first, never null after a read. */
  private List<ContactPublishQueueEntry> entries = new ArrayList<>();
}
