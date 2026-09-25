/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The dates-only summary of the user's automatic reply that eXo caches under the user
 * setting {@code emailAbsence}, for the mailbox band: never the text, never a subject.
 * Refreshed from the server when older than {@code email.connector.absence.status.ttlSeconds}
 * and at once when the user changes the reply in eXo.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AbsenceStatus {

  /** Whether eXo's reply is switched on and running on the server. */
  private boolean enabled;

  /** The first day, {@code YYYY-MM-DD}, or null. */
  private String  start;

  /** The last day, inclusive, {@code YYYY-MM-DD}, or null. */
  private String  end;

  /** The IANA zone the days are in, or null. */
  private String  timeZone;

  /** Where the reply was set: {@code EXO} or {@code SERVER}; null when none. */
  private String  source;

  /** When the reply last changed as far as eXo knows, epoch milliseconds. */
  private long    updatedDate;

  /** When eXo last read the server, epoch milliseconds; bounds the cache's age. */
  private long    lastServerReadDate;
}
