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
package org.exoplatform.emailConnector.model;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A recurring event's rule in the parts the reader can say in words (EXO-90840): "every
 * 2 weeks on Monday and Thursday, 10 times". A rule with any other part (a position in
 * the set, week numbers, an nth weekday, hours) is not described: the invitation then
 * says only that the event recurs.
 */
@Data
@NoArgsConstructor
public class CalendarInvitationRecurrence {

  /** DAILY, WEEKLY, MONTHLY or YEARLY. */
  private String        frequency;

  /** Every how many periods, 1 at least. */
  private int           interval = 1;

  /** How many occurrences, null when not bounded by a count. */
  private Integer       count;

  /** The last day it may occur on, ISO local date, null when not bounded by a date. */
  private String        until;

  /** The weekdays it occurs on (MO, TU…), empty when the rule names none. */
  private List<String>  days;

  /** The days of the month it occurs on, empty when the rule names none. */
  private List<Integer> monthDays;
}
