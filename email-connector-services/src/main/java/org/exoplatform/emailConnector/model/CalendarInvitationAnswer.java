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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An answer the user gave to one calendar invitation from the mail reader (EXO-90840),
 * as it is remembered: the answer, and the SEQUENCE of the event it answered. An
 * update that raises the sequence asks again, so an answer to an older sequence is not
 * shown as the answer to it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CalendarInvitationAnswer {

  /** The answer given. */
  private InvitationAnswer answer;

  /** The SEQUENCE of the event it answered. */
  private int              sequence;

  /** When it was given, epoch milliseconds. */
  private long             answeredAt;
}
