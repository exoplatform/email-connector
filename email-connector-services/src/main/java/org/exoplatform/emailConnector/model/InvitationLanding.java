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
 * An invitation a user wants in their calendar -- answered, added as it is, or
 * removed because its organiser cancelled it -- handed to the add-on holding that
 * calendar (EXO-90848, {@code InvitationCalendarPlugin}).
 * <p>
 * The iCalendar object is the mail's part as received -- the organiser's REQUEST or
 * CANCEL, or a published event -- decoded as UTF-8 and capped in size by the reader,
 * so the implementer reads the whole event (its recurrence, its zones, its attendees)
 * from the one source the user saw; the method, UID, SEQUENCE and RECURRENCE-ID
 * beside it are what the reader read from that same object, for the implementer to
 * cross-check.
 *
 * @param username the user who asked, the only person the landing acts for
 * @param attendeeAddress the address of the user's own mailbox, the one the invitation
 *          names them by
 * @param method the iTIP method as written, upper-cased: REQUEST, PUBLISH, CANCEL…;
 *          null when the object names none
 * @param uid the event's UID
 * @param recurrenceId the RECURRENCE-ID as written when the mail is about one
 *          occurrence, null for a series or a single event
 * @param sequence the event's SEQUENCE, 0 when absent
 * @param answer the answer given, null when the user only asked to add or remove the
 *          event
 * @param icalendar the iCalendar object, as text
 */
public record InvitationLanding(String username,
                                String attendeeAddress,
                                String method,
                                String uid,
                                String recurrenceId,
                                int sequence,
                                InvitationAnswer answer,
                                String icalendar) {

  /**
   * Refuses a landing missing what every implementer needs.
   *
   * @param username the user
   * @param attendeeAddress their mailbox address
   * @param method the iTIP method
   * @param uid the event's UID
   * @param recurrenceId the occurrence, or null
   * @param sequence the SEQUENCE
   * @param answer the answer, or null
   * @param icalendar the object
   * @throws IllegalArgumentException without a user, a UID or an object
   */
  public InvitationLanding {
    if (StringUtils.isBlank(username) || StringUtils.isBlank(uid) || StringUtils.isBlank(icalendar)) {
      throw new IllegalArgumentException("A landing names the user, the event's UID and the object");
    }
    method = StringUtils.upperCase(StringUtils.trimToNull(method));
  }
}
