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
package org.exoplatform.emailConnector.plugin;

import org.exoplatform.emailConnector.model.InvitationLanding;

/**
 * Lands the calendar invitation a user answered from a mail in their calendar
 * (EXO-90848): the add-on that holds a user's calendar implements this, so the event
 * the mail describes is in that calendar with the answer the user gave, under that
 * add-on's own identity for it -- so the add-on recognises the event later, and a
 * mail server that already filed the invitation in the calendar does not get it twice.
 * <p>
 * This add-on depends on no calendar: it asks every bean of this type in the platform
 * at the moment of the answer, and asks nobody when there is none. Implementers are
 * ordinary Spring {@code @Service} beans in their own add-on's context, non-final, with
 * a bean name of their own; they are found by type across add-ons.
 * <p>
 * <b>What an implementer is handed is the sender's content.</b> The iCalendar object
 * came in a mail from outside: its UID, its links, its organiser and attendees are
 * claims, capped in size but not vetted. An implementer treats it as such -- it must
 * not take a UID or a link for one of this deployment's own events, and the only
 * person it acts for is {@link InvitationLanding#username()}, in that user's own
 * calendar: the attendee address names that same user's mailbox, never somebody else
 * to resolve.
 */
public interface InvitationCalendarPlugin {

  /**
   * Lands the answered invitation in the user's calendar, or says the user has no
   * calendar with this add-on.
   * <p>
   * The answer is final at this point: the REPLY left for the organiser already.
   * An implementer that holds a calendar for the user either lands the event with
   * this answer and returns true, or throws when it tried and could not -- the user
   * is then told their calendar could not be updated. It returns false only when it
   * holds no calendar for this user, which lets the next implementer answer; false
   * is also what it answers on anything it cannot establish.
   *
   * @param landing the invitation, the user and their answer
   * @return true when the user's calendar now holds the event with this answer,
   *         false when this add-on holds no calendar for the user
   * @throws RuntimeException when the landing was attempted and failed
   */
  boolean land(InvitationLanding landing);

}
