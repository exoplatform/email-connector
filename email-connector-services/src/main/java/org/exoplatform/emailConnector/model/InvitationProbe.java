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

import org.apache.commons.lang3.StringUtils;

/**
 * The question the mail reader asks the add-on holding the user's calendar when an
 * invitation is opened (EXO-90873, {@code InvitationCalendarPlugin#held}): does the
 * user's calendar hold this event already? Deliberately not the sender's object: the
 * add-on looks the event up in what it holds, by the UID the reader read, and never
 * parses the mail to answer.
 *
 * @param username the user reading the invitation, the only person whose calendar is
 *          looked in
 * @param attendeeAddress the address of the user's own mailbox, the one the invitation
 *          names them by; null when the user has none
 * @param uid the event's UID
 * @param recurrenceId the RECURRENCE-ID as written when the mail is about one
 *          occurrence, null for a series or a single event
 * @param organizer the organiser's address as the mail names it, null for a
 *          published event naming none: a copy is held for this mail only when it
 *          is that same organiser's, as only an event's organiser may change it, so a
 *          sender who learnt the UID of another event the user holds is not told of it
 */
public record InvitationProbe(String username, String attendeeAddress, String uid, String recurrenceId, String organizer) {

  /**
   * Refuses a question missing what every implementer needs.
   *
   * @param username the user
   * @param attendeeAddress their mailbox address, or null
   * @param uid the event's UID
   * @param recurrenceId the occurrence, or null
   * @param organizer the organiser's address, or null
   * @throws IllegalArgumentException without a user or a UID
   */
  public InvitationProbe {
    if (StringUtils.isBlank(username) || StringUtils.isBlank(uid)) {
      throw new IllegalArgumentException("A probe names the user and the event's UID");
    }
    recurrenceId = StringUtils.trimToNull(recurrenceId);
    organizer = StringUtils.trimToNull(organizer);
  }
}
