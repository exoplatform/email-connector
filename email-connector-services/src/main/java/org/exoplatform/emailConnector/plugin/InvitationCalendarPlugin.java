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
import org.exoplatform.emailConnector.model.LandedInvitation;

/**
 * Lands the calendar invitation a mail carries in the user's calendar (EXO-90848):
 * the add-on that holds a user's calendar implements this, so the event the mail
 * describes is in that calendar -- with the answer the user gave, or without one
 * when they only asked to add it -- under that add-on's own identity for it, so the
 * add-on recognises the event later, and a mail server that already filed the
 * invitation in the calendar does not get it twice. The same add-on removes the
 * event when its organiser cancelled it.
 * <p>
 * This add-on depends on no calendar: it asks every bean of this type in the platform
 * at the moment of the click, and asks nobody when there is none. Implementers are
 * ordinary Spring {@code @Service} beans in their own add-on's context, non-final, with
 * a bean name of their own; they are found by type across add-ons.
 * <p>
 * <b>What an implementer is handed is the sender's content.</b> The iCalendar object
 * came in a mail from outside: its UID, its links, its organiser and attendees are
 * claims, capped in size but not vetted. An implementer treats it as such -- it must
 * not take a UID or a link for one of this deployment's own events, must let only an
 * event's organiser change or cancel it, and the only person it acts for is
 * {@link InvitationLanding#username()}, in that user's own calendar: the attendee
 * address names that same user's mailbox, never somebody else to resolve. The link it
 * returns is built from the platform's own domain, never from the object.
 */
public interface InvitationCalendarPlugin {

  /**
   * Whether this add-on holds a calendar for the user -- what decides whether the
   * reader offers to add an invitation to it. Asked on every read of an invitation,
   * so it must cost no round trip to a server; false on anything it cannot establish.
   *
   * @param username the user reading the invitation
   * @return true when this add-on holds a calendar of theirs
   */
  boolean holdsCalendarFor(String username);

  /**
   * Lands the invitation in the user's calendar, or says the user has no calendar with
   * this add-on.
   * <p>
   * What lands depends on the message and the answer: an invitation or a published
   * event is created in the calendar, or updated when the user already holds it and
   * the message is its organiser's newer revision; an answer given is set on it, and
   * a decline sets it on an event the user holds and creates nothing; a cancellation
   * removes the event the user holds. An implementer that holds a calendar for the
   * user returns what it did, or null when there was nothing to do for this message
   * (a decline or a cancellation of an event the user never added); it returns null
   * too when it holds no calendar for this user, which lets the next implementer
   * answer. Two things it says by throwing, told apart by the exception: an
   * invitation it refuses to land as it is -- unreadable, about another event, about
   * one occurrence only, or one it must not trust -- is an
   * {@link IllegalArgumentException}, and the user is told the event was not added; a
   * landing it attempted and could not finish is any other exception, and the user is
   * told their calendar could not be updated. Only the second is an incident.
   *
   * @param landing the invitation, the user and what they asked
   * @return what landed, null when nothing did
   * @throws IllegalArgumentException when the invitation cannot be landed as it is
   * @throws RuntimeException when the landing was attempted and failed
   */
  LandedInvitation land(InvitationLanding landing);

}
