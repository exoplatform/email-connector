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

import org.exoplatform.emailConnector.utils.InvitationReplyWriter;

/**
 * An invitation as parsed from a mail's iCalendar part (EXO-90840): its description for
 * the reader, the RECURRENCE-ID of the occurrence it is about, how to write the
 * attendee's REPLY to it, and the part's text for the add-on that lands the answered
 * event in the user's calendar (EXO-90848).
 *
 * @param invitation the description
 * @param recurrenceId the RECURRENCE-ID as written, null for a whole series or a single event
 * @param url the event's URL property as written, the sender's: null when it has none
 * @param replyWriter writes the REPLY
 * @param icalendar the part as received, decoded as UTF-8
 * @param fromOrganizer whether the mail is from the event's organiser, as the reader
 *          established it from the message's {@code From}; false as parsed
 */
public record ParsedInvitation(CalendarInvitation invitation,
                               String recurrenceId,
                               String url,
                               InvitationReplyWriter replyWriter,
                               String icalendar,
                               boolean fromOrganizer) {

  /**
   * The same invitation, with whether its mail is from the event's organiser.
   *
   * @param fromOrganizer the reader's verdict
   * @return the invitation
   */
  public ParsedInvitation withFromOrganizer(boolean fromOrganizer) {
    return new ParsedInvitation(invitation, recurrenceId, url, replyWriter, icalendar, fromOrganizer);
  }
}
