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

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The calendar invitation a mail carries (EXO-90840), as the mail reader shows it above
 * the body: what, when, where, who, and what the user may answer. Every text in it is
 * the sender's: the reader shows it as text, never as markup.
 * <p>
 * Times are instants (epoch milliseconds), which the reader shows in the viewer's own
 * time zone; an all-day event carries dates instead, which belong to no zone.
 */
@Data
@NoArgsConstructor
public class CalendarInvitation {

  /** The iTIP method: REQUEST, CANCEL, PUBLISH…; null when the calendar names none. */
  private String                         method;

  /** Whether the event was cancelled: a CANCEL, or an event whose status is CANCELLED. */
  private boolean                        cancelled;

  /** The event's UID. */
  private String                         uid;

  /** The event's SEQUENCE, 0 when absent. */
  private int                            sequence;

  /** Whether the mail is about one occurrence of a recurring event (RECURRENCE-ID). */
  private boolean                        occurrence;

  /** The title. */
  private String                         summary;

  /** The place. */
  private String                         location;

  /** Whether the event lasts whole days. */
  private boolean                        allDay;

  /** The start instant, null for an all-day or floating event. */
  private Long                           start;

  /** The end instant, null for an all-day event or one with no end. */
  private Long                           end;

  /**
   * Whether the event's times are floating: the same wall-clock time wherever it is
   * read, given as local date-times rather than instants.
   */
  private boolean                        floating;

  /** The start of a floating event, ISO local date-time. */
  private String                         startLocal;

  /** The end of a floating event, ISO local date-time, null when it has none. */
  private String                         endLocal;

  /** The first day of an all-day event, ISO local date. */
  private String                         startDate;

  /** The last day of an all-day event, inclusive, ISO local date. */
  private String                         endDate;

  /** The time zone the organiser set the event in, when it names one. */
  private String                         timeZone;

  /** Whether the event recurs. */
  private boolean                        recurring;

  /** The rule in words, null when it recurs by a rule the reader cannot say. */
  private CalendarInvitationRecurrence   recurrence;

  /** The organiser, null when the invitation names none. */
  private CalendarInvitationPerson       organizer;

  /** The attendees, the first ones only when there are many. */
  private List<CalendarInvitationPerson> attendees = new ArrayList<>();

  /** How many attendees the invitation names, all of them. */
  private int                            attendeeCount;

  /** The address the user answers for: theirs, or a shared mailbox owner's. */
  private String                         attendeeAddress;

  /**
   * The user's current answer: the one given from here for this sequence of the event,
   * else the one the invitation states for them; null when there is none.
   */
  private InvitationAnswer               answer;

  /** Whether Accept / Maybe / Decline may be offered. */
  private boolean                        answerable;

  /**
   * Why an invitation that asks for an answer cannot get one from here, as a message
   * code: the shared mailbox's owner has not let the user send in her name
   * ({@code emailConnector.invitation.sendNotAllowed}); null otherwise.
   */
  private String                         answerRefusal;
}
