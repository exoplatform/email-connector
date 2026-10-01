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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.model.CalendarInvitation;
import org.exoplatform.emailConnector.model.CalendarInvitationRecurrence;
import org.exoplatform.emailConnector.model.InvitationAnswer;
import org.exoplatform.emailConnector.model.ParsedInvitation;

import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.Organizer;
import net.fortuna.ical4j.model.property.XProperty;

/**
 * The invitations a mail carries, as real clients write them (EXO-90840): read with
 * ical4j, described for the reader, and answered with a REPLY that other clients read
 * back as the attendee's answer.
 */
class CalendarInvitationUtilsTest {

  private static final String ME = "testEmail@acme.com";

  /**
   * Google's invitation to a series: the instants in the organiser's zone, the rule in
   * words, the organiser and every attendee with their status, the EMAIL parameter read
   * without commons-validator, and the sender's markup kept as text.
   *
   * @throws Exception when the fixture cannot be read
   */
  @Test
  void aGoogleInvitationToASeriesIsDescribed() throws Exception {
    ParsedInvitation parsed = CalendarInvitationUtils.parseInvitation(fixture("google-weekly-request.ics"), ME, 50);
    CalendarInvitation invitation = parsed.invitation();

    assertEquals("REQUEST", invitation.getMethod());
    assertEquals("weekly-sync@google.com", invitation.getUid());
    assertEquals(2, invitation.getSequence());
    assertFalse(invitation.isCancelled());
    assertFalse(invitation.isOccurrence());
    assertNull(parsed.recurrenceId());
    assertEquals(new String(fixture("google-weekly-request.ics"), StandardCharsets.UTF_8),
                 parsed.icalendar(),
                 "the part as received, for the add-on that lands it");
    assertEquals("Weekly <b>sync</b>", invitation.getSummary(), "text, kept as written: the reader escapes it");
    assertEquals("Room <i>4</i>", invitation.getLocation());
    assertFalse(invitation.isAllDay());
    assertEquals(ZonedDateTime.parse("2026-10-05T08:00:00Z").toInstant().toEpochMilli(), invitation.getStart());
    assertEquals(ZonedDateTime.parse("2026-10-05T09:00:00Z").toInstant().toEpochMilli(), invitation.getEnd());
    assertFalse(invitation.isFloating());
    assertEquals("Europe/Paris", invitation.getTimeZone());
    assertTrue(invitation.isRecurring());
    CalendarInvitationRecurrence rule = invitation.getRecurrence();
    assertEquals("WEEKLY", rule.getFrequency());
    assertEquals(2, rule.getInterval());
    assertEquals(10, rule.getCount());
    assertNull(rule.getUntil());
    assertEquals(List.of("MO", "TH"), rule.getDays());
    assertEquals("olivia@partner.example", invitation.getOrganizer().getAddress());
    assertEquals("Olivia Organizer", invitation.getOrganizer().getName());
    assertEquals(3, invitation.getAttendeeCount());
    assertEquals(List.of("ACCEPTED", "NEEDS-ACTION", "TENTATIVE"),
                 invitation.getAttendees().stream().map(person -> person.getPartStat()).toList());
    assertEquals("<b>Bob</b>", invitation.getAttendees().get(2).getName());
    assertEquals(ME, invitation.getAttendeeAddress());
    assertNull(invitation.getAnswer(), "NEEDS-ACTION is no answer");

    CalendarInvitation listedOnce = CalendarInvitationUtils.parseInvitation(fixture("google-weekly-request.ics"), ME, 1)
                                                           .invitation();
    assertEquals(1, listedOnce.getAttendees().size());
    assertEquals(3, listedOnce.getAttendeeCount(), "the count says how many there are");
    assertEquals(ME, listedOnce.getAttendeeAddress(), "found among all of them, not only those listed");
  }

  /**
   * The EMAIL parameter (RFC 7986) is read as plain text: the library's own factory
   * validates it with commons-validator, which the server does not ship, and refuses a
   * value it finds invalid -- which would lose the attendee, or the whole calendar.
   *
   * @throws Exception when the fixture cannot be read
   */
  @Test
  void theEmailParameterIsNotValidated() throws Exception {
    CalendarInvitation invitation = CalendarInvitationUtils.parseInvitation(fixture("email-parameter.ics"), ME, 50).invitation();

    assertEquals(1, invitation.getAttendeeCount());
    assertEquals(InvitationAnswer.ACCEPTED, invitation.getAnswer());
  }

  /**
   * A series bounded by a date says its last day in the organiser's zone: Google writes
   * UNTIL as the end of the last local day in UTC, 04:59:59Z on the 31st being still the
   * 30th in New York.
   *
   * @throws Exception when the fixture cannot be read
   */
  @Test
  void theLastDayOfASeriesIsSaidInTheOrganisersZone() throws Exception {
    CalendarInvitationRecurrence rule = CalendarInvitationUtils.parseInvitation(fixture("until-new-york.ics"), ME, 50)
                                                               .invitation()
                                                               .getRecurrence();

    assertEquals("WEEKLY", rule.getFrequency());
    assertEquals("2026-12-30", rule.getUntil());
    assertNull(rule.getCount());
  }

  /**
   * A floating time is handed over as the wall-clock time it is, never read as an
   * instant in the server's zone.
   *
   * @throws Exception when the fixture cannot be read
   */
  @Test
  void aFloatingTimeStaysAWallClockTime() throws Exception {
    java.util.TimeZone serverZone = java.util.TimeZone.getDefault();
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
    try {
      CalendarInvitation invitation = CalendarInvitationUtils.parseInvitation(fixture("floating.ics"), ME, 50).invitation();

      assertTrue(invitation.isFloating());
      assertEquals("2026-10-05T10:00", invitation.getStartLocal());
      assertEquals("2026-10-05T11:30", invitation.getEndLocal());
      assertNull(invitation.getStart(), "no instant: it would be the server's zone's");
      assertNull(invitation.getTimeZone());
    } finally {
      java.util.TimeZone.setDefault(serverZone);
    }
  }

  /**
   * A message that answers rather than invites speaks for someone: the attendee of a
   * REPLY with the answer given, of a REFRESH, of a COUNTER naming one, or the organiser of
   * a DECLINECOUNTER. An invitation speaks for nobody.
   *
   * @throws Exception when a fixture cannot be read
   */
  @Test
  void anAnswerSpeaksForItsRespondent() throws Exception {
    CalendarInvitation reply = CalendarInvitationUtils.parseInvitation(fixture("bluemind-reply-accepted.ics"), ME, 50).invitation();
    assertEquals("REPLY", reply.getMethod());
    assertEquals("MEYER", reply.getRespondent().getName());
    assertEquals("meyer@acme.com", reply.getRespondent().getAddress());
    assertEquals("ACCEPTED", reply.getRespondent().getPartStat());
    assertEquals("LASTKINGERIC", reply.getSummary());

    CalendarInvitation declined = CalendarInvitationUtils.parseInvitation(fixture("reply-no-name.ics"), ME, 50).invitation();
    assertNull(declined.getRespondent().getName());
    assertEquals("DECLINED", declined.getRespondent().getPartStat());

    assertEquals("meyer@acme.com",
                 CalendarInvitationUtils.parseInvitation(fixture("counter.ics"), ME, 50).invitation().getRespondent().getAddress());
    assertEquals("root@acme.com",
                 CalendarInvitationUtils.parseInvitation(fixture("declinecounter.ics"), ME, 50).invitation().getRespondent().getAddress());
    assertEquals("meyer@acme.com",
                 CalendarInvitationUtils.parseInvitation(fixture("refresh.ics"), ME, 50).invitation().getRespondent().getAddress());
    assertNull(CalendarInvitationUtils.parseInvitation(fixture("counter-two-attendees.ics"), ME, 50).invitation().getRespondent(),
               "a COUNTER listing several attendees does not say which proposes");
    assertNull(CalendarInvitationUtils.parseInvitation(fixture("google-weekly-request.ics"), ME, 50).invitation().getRespondent());
  }

  /**
   * Exchange's invitation to one occurrence, in a Windows zone the invitation defines:
   * the instant resolved through its VTIMEZONE, the occurrence and its RECURRENCE-ID,
   * and a rule naming "the second Tuesday" said only as recurring.
   *
   * @throws Exception when the fixture cannot be read
   */
  @Test
  void anExchangeInvitationToOneOccurrenceIsDescribed() throws Exception {
    ParsedInvitation parsed = CalendarInvitationUtils.parseInvitation(fixture("outlook-occurrence-request.ics"), ME, 50);
    CalendarInvitation invitation = parsed.invitation();

    assertTrue(invitation.isOccurrence());
    assertEquals("20261013T140000", parsed.recurrenceId());
    assertEquals(ZonedDateTime.parse("2026-10-13T13:00:00Z").toInstant().toEpochMilli(), invitation.getStart());
    assertEquals("Romance Standard Time", invitation.getTimeZone());
    assertTrue(invitation.isRecurring());
    assertNull(invitation.getRecurrence(), "the second Tuesday is not said in words");
    assertEquals("Teams", invitation.getLocation());
    assertEquals(ME, invitation.getAttendeeAddress());
  }

  /**
   * A cancelled all-day event: dates rather than instants, its last day the one before
   * its exclusive end, and the attendee's stated answer.
   *
   * @throws Exception when the fixture cannot be read
   */
  @Test
  void aCancelledAllDayEventIsDescribed() throws Exception {
    CalendarInvitation invitation = CalendarInvitationUtils.parseInvitation(fixture("allday-cancel.ics"), ME.toUpperCase(), 50)
                                                           .invitation();

    assertEquals("CANCEL", invitation.getMethod());
    assertTrue(invitation.isCancelled());
    assertTrue(invitation.isAllDay());
    assertNull(invitation.getStart());
    assertEquals("2026-10-12", invitation.getStartDate());
    assertEquals("2026-10-13", invitation.getEndDate());
    assertEquals(InvitationAnswer.ACCEPTED, invitation.getAnswer(), "the address is matched whatever its case");
    assertFalse(invitation.isRecurring());
  }

  /**
   * The REPLY (RFC 5546 §3.2.3) carries the invitation's UID, SEQUENCE, RECURRENCE-ID,
   * ORGANIZER and time zones, and the attendee alone with the answer -- with SENT-BY for
   * a delegate -- and reads back as such.
   *
   * @throws Exception when the fixture cannot be read
   */
  @Test
  void theReplyCarriesTheEventsIdentityAndTheAttendeesAnswer() throws Exception {
    ParsedInvitation parsed = CalendarInvitationUtils.parseInvitation(fixture("outlook-occurrence-request.ics"), ME, 50);

    String reply = parsed.replyWriter().write(ME, "Test\r\nUser", InvitationAnswer.TENTATIVE, "delegate@acme.com");

    Calendar calendar = CalendarInvitationUtils.parse(reply.getBytes(StandardCharsets.UTF_8));
    assertEquals("REPLY", calendar.getMethod().getValue());
    assertEquals(1, calendar.getComponents().stream().filter(component -> component instanceof VEvent).count());
    assertEquals(1, calendar.getComponents().stream().filter(component -> Component.VTIMEZONE.equals(component.getName())).count());
    VEvent event = CalendarInvitationUtils.primaryEvent(calendar);
    assertEquals("040000008200E00074C5B7101A82E00800000000", event.getProperty(Property.UID).getValue());
    assertEquals(0, event.getSequence().getSequenceNo());
    assertEquals("20261013T140000", event.getRecurrenceId().getValue());
    assertEquals("Romance Standard Time", event.getRecurrenceId().getParameter(Parameter.TZID).getValue());
    assertEquals("mailto:olivia@partner.example", ((Organizer) event.getProperty(Property.ORGANIZER)).getValue());
    assertNotNull(event.getProperty(Property.DTSTAMP));
    List<Property> attendees = event.getProperties(Property.ATTENDEE);
    assertEquals(1, attendees.size());
    Attendee attendee = (Attendee) attendees.get(0);
    assertEquals("mailto:" + ME, attendee.getValue());
    assertEquals("TENTATIVE", attendee.getParameter(Parameter.PARTSTAT).getValue());
    assertEquals("mailto:delegate@acme.com", attendee.getParameter(Parameter.SENT_BY).getValue());
    assertEquals("Test User", attendee.getParameter(Parameter.CN).getValue(), "the name on one line");

    String own = CalendarInvitationUtils.parseInvitation(fixture("google-weekly-request.ics"), ME, 50)
                                        .replyWriter()
                                        .write(ME, null, InvitationAnswer.DECLINED, null);
    VEvent ownEvent = CalendarInvitationUtils.primaryEvent(CalendarInvitationUtils.parse(own.getBytes(StandardCharsets.UTF_8)));
    assertEquals(2, ownEvent.getSequence().getSequenceNo());
    assertNull(ownEvent.getRecurrenceId());
    Attendee ownAttendee = (Attendee) ownEvent.getProperties(Property.ATTENDEE).get(0);
    assertEquals("DECLINED", ownAttendee.getParameter(Parameter.PARTSTAT).getValue());
    assertNull(ownAttendee.getParameter(Parameter.SENT_BY));
    assertNull(ownAttendee.getParameter(Parameter.CN));
  }

  /**
   * Only one plain {@code mailto:} address is an address: a list, a group, another
   * scheme or an empty value is none, so nothing but one person can be answered.
   *
   * @throws Exception when a property cannot be built
   */
  @Test
  void onlyOnePlainMailtoAddressIsAnAddress() throws Exception {
    assertEquals("olivia@partner.example", CalendarInvitationUtils.mailAddress(new Organizer("mailto:olivia@partner.example")));
    assertEquals("olivia@partner.example", CalendarInvitationUtils.mailAddress(new Organizer("MAILTO:olivia@partner.example")));
    assertNull(CalendarInvitationUtils.mailAddress(new Organizer("mailto:a@partner.example,b@partner.example")));
    assertNull(CalendarInvitationUtils.mailAddress(new Organizer("mailto:team:%20a@x.example;")));
    assertNull(CalendarInvitationUtils.mailAddress(new Organizer("https://partner.example/olivia")));
    // The library refuses an empty mailto: in an ORGANIZER, and drops it when parsing.
    assertNull(CalendarInvitationUtils.mailAddress(new XProperty("X-ORGANIZER", "mailto:")));
    assertNull(CalendarInvitationUtils.mailAddress(null));
  }

  /**
   * What is not an iCalendar object, or holds no event, is refused.
   */
  @Test
  void whatIsNotAnInvitationIsRefused() {
    assertThrows(ParserException.class,
                 () -> CalendarInvitationUtils.parseInvitation("<html>not a calendar</html>".getBytes(StandardCharsets.UTF_8), ME, 50));
    byte[] noEvent = String.join("\r\n", "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//x//EN", "BEGIN:VTODO", "UID:1",
                                 "DTSTAMP:20261001T120000Z", "END:VTODO", "END:VCALENDAR", "")
                           .getBytes(StandardCharsets.UTF_8);
    assertThrows(IllegalArgumentException.class, () -> CalendarInvitationUtils.parseInvitation(noEvent, ME, 50));
  }

  /**
   * A fixture's bytes.
   *
   * @param name the file under invitations/
   * @return its bytes
   * @throws IOException when it cannot be read
   */
  static byte[] fixture(String name) throws IOException {
    try (InputStream input = CalendarInvitationUtilsTest.class.getResourceAsStream("/invitations/" + name)) {
      return input.readAllBytes();
    }
  }
}
