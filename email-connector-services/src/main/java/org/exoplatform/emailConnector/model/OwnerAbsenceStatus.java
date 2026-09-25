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
 * What a delegate sees of the automatic reply of the mailbox somebody shared with them:
 * the dates of the owner's reply, as eXo cached them when the owner last had them read
 * or written in eXo -- never the text, never the subject, never where it was set. Built
 * field by field from the owner's summary, so nothing else of it can leak through.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OwnerAbsenceStatus {

  /** Whether the owner's reply was switched on when eXo last read it. */
  private boolean enabled;

  /** The first day, {@code YYYY-MM-DD}, or null. */
  private String  start;

  /** The last day, inclusive, {@code YYYY-MM-DD}, or null. */
  private String  end;

  /** The IANA zone the days are in, or null. */
  private String  timeZone;

  /** When the owner's reply last changed as far as eXo knows, epoch milliseconds. */
  private long    updatedDate;

  /**
   * When eXo last checked the owner's server, epoch milliseconds; 0 when never. A check
   * that could not reach the server counts too (the owner's own band retries no sooner
   * than the TTL), so it is the last check, not always the last successful read.
   */
  private long    lastServerReadDate;

  /**
   * Whether that check is older than {@code email.connector.absence.status.ttlSeconds}:
   * the reply may have changed outside eXo since, which a delegate cannot check.
   */
  private boolean stale;
}
