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
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.emailConnector.model.CalendarInvitation;
import org.exoplatform.emailConnector.model.CalendarInvitationPerson;
import org.exoplatform.emailConnector.model.CalendarInvitationRecurrence;
import org.exoplatform.emailConnector.model.InvitationAnswer;
import org.exoplatform.emailConnector.model.ParsedInvitation;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.data.CalendarParserFactory;
import net.fortuna.ical4j.data.ContentHandlerContext;
import net.fortuna.ical4j.data.DefaultParameterFactorySupplier;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Date;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.ParameterFactory;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.TimeZoneRegistryFactory;
import net.fortuna.ical4j.model.WeekDay;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.fortuna.ical4j.model.parameter.Cn;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.parameter.SentBy;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStamp;
import net.fortuna.ical4j.model.property.Method;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.RRule;
import net.fortuna.ical4j.model.property.Sequence;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.Version;

/**
 * The calendar invitations a mail carries (EXO-90840, iTIP, RFC 5546 / iMIP, RFC 6047):
 * reading one with ical4j -- the iCalendar library the agenda add-on puts on the
 * server's class path -- describing it for the mail reader, and writing the attendee's
 * REPLY. Everything ical4j is confined to this class, so the service that calls it loads
 * without the library and only these calls fail when it is absent.
 * <p>
 * The content is the sender's, and treated as such: it is parsed by the library, never
 * by hand; it is read leniently (an invalid property is dropped rather than failing the
 * whole calendar) but no further; the texts it yields go to the reader as data; and
 * nothing it says chooses who the reply goes to except its organiser, which the reader
 * shows before the user answers.
 */
public final class CalendarInvitationUtils {

  /** iTIP REQUEST: an invitation, or an update of one. */
  public static final String         METHOD_REQUEST = "REQUEST";

  /** iTIP CANCEL: the organiser cancelled the event, or an occurrence of it. */
  public static final String         METHOD_CANCEL  = "CANCEL";

  /** What the reply says wrote it. */
  static final String                PRODUCT_ID     = "-//eXo Platform//eXo Email Connector//EN";

  /** The scheme of a calendar user's mail address. */
  private static final String        MAILTO         = "mailto:";

  /** The STATUS of a cancelled event. */
  private static final String        CANCELLED      = "CANCELLED";

  /** The PARTSTAT of an attendee who stated none (RFC 5545's default). */
  private static final String        NEEDS_ACTION   = "NEEDS-ACTION";

  /** An iCalendar DATE value, the date part of a DATE-TIME. */
  private static final DateTimeFormatter ICAL_DATE  = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

  /** The length of {@link #ICAL_DATE}'s values. */
  private static final int           ICAL_DATE_LENGTH = 8;

  /** The rule parts the reader can say in words, besides FREQ, INTERVAL, COUNT and UNTIL. */
  private static final List<String>  SAID_PARTS     = List.of("BYDAY", "BYMONTHDAY");

  /**
   * Not instantiable: static helpers only.
   */
  private CalendarInvitationUtils() {
  }

  /**
   * Parses an iCalendar object (RFC 5545), UTF-8 as RFC 5545 requires. A property the
   * library finds invalid is dropped rather than failing the calendar: real
   * invitations carry a few, and the reader needs the rest.
   *
   * @param content the bytes, already capped by the caller
   * @return the calendar
   * @throws IOException if it cannot be read
   * @throws ParserException if it is not an iCalendar object
   */
  public static Calendar parse(byte[] content) throws IOException, ParserException {
    Supplier<List<ParameterFactory<?>>> parameters = () -> {
      List<ParameterFactory<?>> factories = new ArrayList<>(new DefaultParameterFactorySupplier().get());
      factories.removeIf(factory -> factory.supports(UncheckedEmailParameterFactory.EMAIL));
      factories.add(new UncheckedEmailParameterFactory());
      return factories;
    };
    ContentHandlerContext context = new ContentHandlerContext().withSupressInvalidProperties(true)
                                                               .withParameterFactorySupplier(parameters);
    CalendarBuilder builder = new CalendarBuilder(CalendarParserFactory.getInstance().get(),
                                                  context,
                                                  TimeZoneRegistryFactory.getInstance().createRegistry());
    try (Reader reader = new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
      return builder.build(reader);
    }
  }

  /**
   * Parses and describes the invitation of an iCalendar part, for the user answering for
   * an address.
   *
   * @param content the part's bytes, already capped by the caller
   * @param attendeeAddress the address the reader answers for
   * @param maxAttendees how many attendees to list at most
   * @return the invitation, its occurrence and its reply writer
   * @throws IOException if it cannot be read
   * @throws ParserException if it is not an iCalendar object
   * @throws IllegalArgumentException when it holds no event
   */
  public static ParsedInvitation parseInvitation(byte[] content, String attendeeAddress, int maxAttendees) throws IOException,
                                                                                                         ParserException {
    Calendar calendar = parse(content);
    VEvent event = primaryEvent(calendar);
    if (event == null) {
      throw new IllegalArgumentException("The calendar holds no event");
    }
    CalendarInvitation invitation = describe(calendar, event, attendeeAddress, maxAttendees);
    Property recurrenceId = event.getProperty(Property.RECURRENCE_ID);
    return new ParsedInvitation(invitation,
                                recurrenceId == null ? null : StringUtils.trimToNull(recurrenceId.getValue()),
                                (address, name, answer, sentBy) -> buildReply(calendar, event, address, name, answer, sentBy));
  }

  /**
   * The event an invitation is about: the first VEVENT without a RECURRENCE-ID (the
   * series), else the first one (a single occurrence).
   *
   * @param calendar the calendar
   * @return the event, null when the calendar holds none
   */
  public static VEvent primaryEvent(Calendar calendar) {
    VEvent first = null;
    for (Component component : calendar.getComponents()) {
      if (component instanceof VEvent event) {
        if (event.getRecurrenceId() == null) {
          return event;
        }
        if (first == null) {
          first = event;
        }
      }
    }
    return first;
  }

  /**
   * Describes an invitation for the reader: everything but what depends on who reads it
   * and from which mailbox (the answer, whether it may be given), which the caller sets.
   *
   * @param calendar the calendar
   * @param event its primary event
   * @param attendeeAddress the address the reader answers for, to find their own status
   * @param maxAttendees how many attendees to list at most
   * @return the description
   */
  public static CalendarInvitation describe(Calendar calendar, VEvent event, String attendeeAddress, int maxAttendees) {
    CalendarInvitation invitation = new CalendarInvitation();
    Method method = calendar.getMethod();
    invitation.setMethod(method == null ? null : StringUtils.upperCase(StringUtils.trimToNull(method.getValue()), Locale.ROOT));
    invitation.setUid(value(event.getProperty(Property.UID)));
    invitation.setSequence(sequenceOf(event));
    invitation.setOccurrence(event.getRecurrenceId() != null);
    invitation.setSummary(value(event.getProperty(Property.SUMMARY)));
    invitation.setLocation(value(event.getProperty(Property.LOCATION)));
    invitation.setCancelled(METHOD_CANCEL.equals(invitation.getMethod())
        || CANCELLED.equalsIgnoreCase(value(event.getProperty(Property.STATUS))));
    describeWhen(event, invitation);
    Property rrule = event.getProperty(Property.RRULE);
    invitation.setRecurring(rrule != null || event.getProperty(Property.RDATE) != null);
    if (rrule instanceof RRule rule) {
      invitation.setRecurrence(describeRule(rule.getRecur()));
    }
    Property organizer = event.getProperty(Property.ORGANIZER);
    if (organizer != null) {
      invitation.setOrganizer(new CalendarInvitationPerson(commonName(organizer), mailAddress(organizer), null));
    }
    List<Property> attendees = event.getProperties(Property.ATTENDEE);
    invitation.setAttendeeCount(attendees.size());
    for (Property attendee : attendees) {
      CalendarInvitationPerson person = new CalendarInvitationPerson(commonName(attendee),
                                                                     mailAddress(attendee),
                                                                     partStatOf(attendee));
      if (invitation.getAttendees().size() < maxAttendees) {
        invitation.getAttendees().add(person);
      }
      if (person.getAddress() != null && StringUtils.equalsIgnoreCase(person.getAddress(), attendeeAddress)) {
        invitation.setAttendeeAddress(person.getAddress());
        invitation.setAnswer(InvitationAnswer.ofPartStat(person.getPartStat()));
      }
    }
    return invitation;
  }

  /**
   * Writes the attendee's REPLY to an invitation (RFC 5546 §3.2.3): METHOD:REPLY and
   * one VEVENT carrying the invitation's UID, SEQUENCE and RECURRENCE-ID, its
   * ORGANIZER, DTSTART, DTEND and SUMMARY, a fresh DTSTAMP, and the attendee alone with
   * the answer's PARTSTAT -- with SENT-BY when a delegate answers on the attendee's
   * behalf. The invitation's time zones are copied, so a DTSTART or a RECURRENCE-ID in
   * one of them stays resolvable.
   *
   * @param calendar the invitation
   * @param event its primary event
   * @param attendeeAddress the address the answer is given for
   * @param attendeeName the attendee's display name, may be null
   * @param answer the answer
   * @param sentBy the delegate's address when answering on the attendee's behalf, else null
   * @return the reply, an iCalendar object
   * @throws IOException if it cannot be written
   */
  public static String buildReply(Calendar calendar,
                                  VEvent event,
                                  String attendeeAddress,
                                  String attendeeName,
                                  InvitationAnswer answer,
                                  String sentBy) throws IOException {
    try {
      Calendar reply = new Calendar();
      reply.getProperties().add(new ProdId(PRODUCT_ID));
      reply.getProperties().add(Version.VERSION_2_0);
      reply.getProperties().add(Method.REPLY);
      for (Component component : calendar.getComponents()) {
        if (component instanceof VTimeZone) {
          reply.getComponents().add(component.copy());
        }
      }
      VEvent answered = new VEvent();
      answered.getProperties().add(new Uid(value(event.getProperty(Property.UID))));
      answered.getProperties().add(new Sequence(sequenceOf(event)));
      answered.getProperties().add(new DtStamp());
      for (String name : List.of(Property.RECURRENCE_ID, Property.ORGANIZER, Property.DTSTART, Property.DTEND, Property.SUMMARY)) {
        Property property = event.getProperty(name);
        if (property != null) {
          answered.getProperties().add(property.copy());
        }
      }
      Attendee attendee = new Attendee(new URI("mailto", attendeeAddress, null));
      attendee.getParameters().add(new PartStat(answer.getPartStat()));
      if (StringUtils.isNotBlank(attendeeName)) {
        attendee.getParameters().add(new Cn(oneLine(attendeeName)));
      }
      if (StringUtils.isNotBlank(sentBy)) {
        attendee.getParameters().add(new SentBy(new URI("mailto", sentBy, null)));
      }
      answered.getProperties().add(attendee);
      reply.getComponents().add(answered);
      StringWriter writer = new StringWriter();
      new CalendarOutputter(false).output(reply, writer);
      return writer.toString();
    } catch (URISyntaxException | ParseException | net.fortuna.ical4j.validate.ValidationException e) {
      throw new IOException("The reply to the invitation could not be written", e);
    }
  }

  /**
   * The mail address of a calendar user property (ORGANIZER, ATTENDEE): its
   * {@code mailto:} address, null when it is not one. Only the address part: nothing
   * else in the value is kept.
   *
   * @param property the property
   * @return the address, trimmed, or null
   */
  public static String mailAddress(Property property) {
    String value = StringUtils.trimToEmpty(property == null ? null : property.getValue());
    if (!StringUtils.startsWithIgnoreCase(value, MAILTO)) {
      return null;
    }
    String address = StringUtils.trimToNull(value.substring(MAILTO.length()));
    return address == null || !address.contains("@") || StringUtils.containsAny(address, ",;<>\"\r\n ") ? null : address;
  }

  /**
   * The display name of a calendar user property, its CN.
   *
   * @param property the property
   * @return the name, on one line, or null
   */
  private static String commonName(Property property) {
    Parameter cn = property.getParameter(Parameter.CN);
    return cn == null ? null : StringUtils.trimToNull(oneLine(cn.getValue()));
  }

  /**
   * An attendee's PARTSTAT, NEEDS-ACTION when it states none (RFC 5545's default).
   *
   * @param attendee the attendee
   * @return the status, upper case
   */
  private static String partStatOf(Property attendee) {
    Parameter partStat = attendee.getParameter(Parameter.PARTSTAT);
    String value = partStat == null ? null : StringUtils.trimToNull(partStat.getValue());
    return value == null ? NEEDS_ACTION : value.toUpperCase(Locale.ROOT);
  }

  /**
   * An event's SEQUENCE, 0 when absent (RFC 5545's default).
   *
   * @param event the event
   * @return the sequence
   */
  private static int sequenceOf(VEvent event) {
    Sequence sequence = event.getSequence();
    return sequence == null ? 0 : sequence.getSequenceNo();
  }

  /**
   * Sets when an event takes place: instants for a timed event, dates for an all-day
   * one -- whose last day is the day before its exclusive DTEND, or its first day when
   * it has no end.
   *
   * @param event the event
   * @param invitation the description being written
   */
  private static void describeWhen(VEvent event, CalendarInvitation invitation) {
    DateProperty start = event.getStartDate();
    if (start == null || start.getDate() == null) {
      return;
    }
    DtEnd end = event.getEndDate(true);
    Date startDate = start.getDate();
    if (startDate instanceof DateTime) {
      invitation.setStart(startDate.getTime());
      if (end != null && end.getDate() != null) {
        invitation.setEnd(end.getDate().getTime());
      }
      if (start.getTimeZone() != null) {
        invitation.setTimeZone(start.getTimeZone().getID());
      } else if (start.isUtc()) {
        invitation.setTimeZone("UTC");
      }
      return;
    }
    invitation.setAllDay(true);
    LocalDate first = LocalDate.parse(startDate.toString(), ICAL_DATE);
    LocalDate last = first;
    if (end != null && end.getDate() != null && !(end.getDate() instanceof DateTime)) {
      LocalDate exclusiveEnd = LocalDate.parse(end.getDate().toString(), ICAL_DATE);
      last = exclusiveEnd.isAfter(first) ? exclusiveEnd.minusDays(1) : first;
    }
    invitation.setStartDate(first.toString());
    invitation.setEndDate(last.toString());
  }

  /**
   * A recurrence rule in the parts the reader can say, or null when it has any other.
   *
   * @param recur the rule
   * @return the rule's description, or null
   */
  static CalendarInvitationRecurrence describeRule(Recur recur) {
    if (recur == null || recur.getFrequency() == null) {
      return null;
    }
    String frequency = recur.getFrequency().name();
    if (!List.of("DAILY", "WEEKLY", "MONTHLY", "YEARLY").contains(frequency)) {
      return null;
    }
    String text = recur.toString();
    for (String part : text.split(";")) {
      String name = StringUtils.substringBefore(part, "=").trim().toUpperCase(Locale.ROOT);
      if (name.startsWith("BY") && !SAID_PARTS.contains(name)) {
        return null;
      }
    }
    List<String> days = new ArrayList<>();
    for (WeekDay day : recur.getDayList()) {
      if (day.getOffset() != 0) {
        // "the second Monday": not said in words here.
        return null;
      }
      days.add(day.getDay().name());
    }
    CalendarInvitationRecurrence recurrence = new CalendarInvitationRecurrence();
    recurrence.setFrequency(frequency);
    recurrence.setInterval(Math.max(1, recur.getInterval()));
    recurrence.setCount(recur.getCount() > 0 ? recur.getCount() : null);
    if (recur.getUntil() != null) {
      String until = recur.getUntil().toString();
      recurrence.setUntil(LocalDate.parse(until.substring(0, Math.min(ICAL_DATE_LENGTH, until.length())), ICAL_DATE).toString());
    }
    recurrence.setDays(days);
    recurrence.setMonthDays(new ArrayList<>(recur.getMonthDayList()));
    return recurrence;
  }

  /**
   * A property's value, trimmed, on one line.
   *
   * @param property the property, may be null
   * @return the value, or null when there is none
   */
  private static String value(Property property) {
    return property == null ? null : StringUtils.trimToNull(oneLine(property.getValue()));
  }

  /**
   * A value with its line breaks turned into spaces.
   *
   * @param value the value, may be null
   * @return the value on one line, null for null
   */
  static String oneLine(String value) {
    return value == null ? null : value.replaceAll("[\\r\\n]+", " ");
  }
}
