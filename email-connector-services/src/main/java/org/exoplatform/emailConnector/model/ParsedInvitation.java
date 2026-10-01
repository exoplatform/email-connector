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
 * the reader, the RECURRENCE-ID of the occurrence it is about, and how to write the
 * attendee's REPLY to it.
 *
 * @param invitation the description
 * @param recurrenceId the RECURRENCE-ID as written, null for a whole series or a single event
 * @param replyWriter writes the REPLY
 */
public record ParsedInvitation(CalendarInvitation invitation, String recurrenceId, InvitationReplyWriter replyWriter) {
}
