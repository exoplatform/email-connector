/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.emailConnector.model;

import java.util.Map;

/**
 * The owners of the mailboxes connected here, as walked at one moment, for the
 * {@code EmailSenderProfileService} (EXO-90891).
 *
 * @param owners the eXo login by normalized mailbox address, unmodifiable
 * @param readAt when they were walked, in ms
 */
public record ConnectedMailboxOwners(Map<String, String> owners, long readAt) {

  /**
   * Whether the walk is recent enough to be served.
   *
   * @param now the time, in ms
   * @param ttlMs how long a walk is served, in ms
   * @return true while younger than the given time
   */
  public boolean isFresh(long now, long ttlMs) {
    return now - readAt < ttlMs;
  }
}
