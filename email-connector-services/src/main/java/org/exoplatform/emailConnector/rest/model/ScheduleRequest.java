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
package org.exoplatform.emailConnector.rest.model;

import java.util.List;

import org.exoplatform.emailConnector.model.Email;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The body of a scheduling request: when (epoch milliseconds, UTC) and in which zone the
 * owner chose it, and -- when a draft is being scheduled from the composer -- the draft
 * as the composer shows it, whose text is saved before the draft is frozen. A
 * reschedule carries no draft.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleRequest {

  private Email      draft;

  private Long       scheduledDate;

  private String     timeZone;

  // The draft's stored files to take off it, when its content is updated in place.
  private List<Long> removedAttachmentIds;

  /**
   * A request to schedule a draft, or to give a scheduled mail a new date.
   *
   * @param draft the draft as the composer shows it, may be null
   * @param scheduledDate the instant, epoch milliseconds
   * @param timeZone the zone it was chosen in
   */
  public ScheduleRequest(Email draft, Long scheduledDate, String timeZone) {
    this(draft, scheduledDate, timeZone, null);
  }
}
