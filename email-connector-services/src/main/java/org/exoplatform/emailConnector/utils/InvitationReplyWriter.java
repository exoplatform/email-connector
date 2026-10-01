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
package org.exoplatform.emailConnector.utils;

import java.io.IOException;

import org.exoplatform.emailConnector.model.InvitationAnswer;

/**
 * Writes the attendee's REPLY to one parsed invitation (EXO-90840). What the caller holds
 * instead of the parsed calendar itself, so that no type of the iCalendar library
 * appears outside {@link CalendarInvitationUtils}.
 */
@FunctionalInterface
public interface InvitationReplyWriter {

  /**
   * Writes the REPLY.
   *
   * @param attendeeAddress the address the answer is given for
   * @param attendeeName the attendee's display name, may be null
   * @param answer the answer
   * @param sentBy the delegate's address when answering on the attendee's behalf, else null
   * @return the reply, an iCalendar object
   * @throws IOException if it cannot be written
   */
  String write(String attendeeAddress, String attendeeName, InvitationAnswer answer, String sentBy) throws IOException;
}
