/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.model;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The automatic reply eXo writes in its Sieve script, validated so that nothing the
 * user typed can escape its quoted string or add a header to the reply.
 * <p>
 * Bounds: the subject is one line of at most {@value #MAX_SUBJECT_LENGTH} characters,
 * the text at most {@value #MAX_TEXT_LENGTH}, no NUL anywhere; the handle is a token
 * of letters, digits, dots, underscores and dashes; a window day has a four-digit
 * year. The window's days are the user's own calendar days in {@code zone}, each
 * boundary written with the fixed offset whose date changes at the zone's own
 * midnight, so a window across a DST change, or one whose boundary falls on a change
 * at midnight, starts and ends on the zone's day.
 *
 * @param enabled whether the reply is on; a disabled reply keeps its text in the header
 *          and emits no Sieve
 * @param start the first day, or null for "from now"
 * @param end the last day, inclusive, or null for "until switched off"
 * @param zone the IANA zone the days are in, required with a window
 * @param subject the reply's subject, required: the server's default adds a prefix
 * @param text the reply's text, plain
 * @param handle the RFC 5230 {@code :handle}, stable across text edits, new per
 *          activation
 * @param days the minimum days between two replies to one sender
 */
public record SieveVacation(boolean enabled,
                            LocalDate start,
                            LocalDate end,
                            String zone,
                            String subject,
                            String text,
                            String handle,
                            int days) {

  /** The longest subject accepted. */
  public static final int      MAX_SUBJECT_LENGTH = 200;

  /** The longest text accepted. */
  public static final int      MAX_TEXT_LENGTH    = 4000;

  /** The largest {@code :days} accepted. */
  public static final int      MAX_DAYS           = 365;

  /**
   * The latest year a window day may fall in: a later one is written with a sign
   * ({@code +10000-01-01}), which sorts before the digits in the script's comparison.
   */
  public static final int      MAX_YEAR           = 9999;

  /** The line break of a Sieve script (RFC 5228), which the stored text uses. */
  private static final String  CRLF               = "\r\n";

  /** The characters and length a reply handle may carry. */
  private static final Pattern HANDLE_PATTERN     = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  /** The header JSON field of the on/off switch. */
  private static final String  FIELD_ENABLED      = "enabled";

  /**
   * Validates the reply and normalises the text's line breaks to CRLF.
   *
   * @param enabled whether the reply is on
   * @param start the first day
   * @param end the last day
   * @param zone the zone
   * @param subject the subject
   * @param text the text
   * @param handle the handle
   * @param days the interval
   * @throws IllegalArgumentException with a message code when a value is invalid
   */
  public SieveVacation {
    if (text == null || text.isBlank() || text.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("emailConnector.absence.text.invalid");
    }
    text = storedText(text);
    if (text.length() > MAX_TEXT_LENGTH) {
      throw new IllegalArgumentException("emailConnector.absence.text.invalid");
    }
    validateSubject(subject);
    if (handle == null || !HANDLE_PATTERN.matcher(handle).matches()) {
      throw new IllegalArgumentException("emailConnector.absence.handle.invalid");
    }
    if (days < 1 || days > MAX_DAYS) {
      throw new IllegalArgumentException("emailConnector.absence.days.invalid");
    }
    if ((start != null && end != null && start.isAfter(end)) || !fourDigitYear(start) || !fourDigitYear(end)) {
      throw new IllegalArgumentException("emailConnector.absence.window.invalid");
    }
    validateZone(zone, start, end);
  }

  /**
   * A text as the header stores it: every line break CRLF. The length bound applies to
   * this form, so what is accepted on the way in is also read back from the header.
   *
   * @param text the text, any line breaks
   * @return the text with CRLF line breaks
   */
  public static String storedText(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", CRLF);
  }

  /**
   * The zone the window's days are in.
   *
   * @return the zone, UTC when the reply has none
   */
  public ZoneId zoneId() {
    return zone == null ? ZoneOffset.UTC : ZoneId.of(zone);
  }

  /**
   * The reply as header JSON, nulls omitted.
   *
   * @return the JSON object
   */
  public ObjectNode toJson() {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put(FIELD_ENABLED, enabled);
    if (start != null) {
      node.put("start", start.toString());
    }
    if (end != null) {
      node.put("end", end.toString());
    }
    if (zone != null) {
      node.put("zone", zone);
    }
    if (subject != null) {
      node.put("subject", subject);
    }
    node.put("text", text);
    node.put("handle", handle);
    node.put("days", days);
    return node;
  }

  /**
   * Reads a reply from header JSON, validating it like any other.
   *
   * @param node the JSON object
   * @return the reply
   * @throws IllegalArgumentException when a field is missing or invalid
   */
  public static SieveVacation fromJson(JsonNode node) {
    if (!node.isObject() || !node.path(FIELD_ENABLED).isBoolean() || !node.path("days").isInt()) {
      throw new IllegalArgumentException("emailConnector.absence.header.invalid");
    }
    try {
      return new SieveVacation(node.path(FIELD_ENABLED).booleanValue(),
                          date(node, "start"),
                          date(node, "end"),
                          string(node, "zone"),
                          string(node, "subject"),
                          string(node, "text"),
                          string(node, "handle"),
                          node.path("days").intValue());
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("emailConnector.absence.header.invalid", e);
    }
  }

  /**
   * Whether a window day, when there is one, is written with four digits, as the
   * script's date comparison needs.
   *
   * @param day the day, may be null
   * @return true when null or in years 0 to {@value #MAX_YEAR}
   */
  private static boolean fourDigitYear(LocalDate day) {
    return day == null || (day.getYear() >= 0 && day.getYear() <= MAX_YEAR);
  }

  /**
   * Checks the subject: present, bounded, on one line, without a NUL.
   *
   * @param subject the subject
   * @throws IllegalArgumentException with a message code when it is invalid
   */
  private static void validateSubject(String subject) {
    if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH || subject.indexOf('\r') >= 0
        || subject.indexOf('\n') >= 0 || subject.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("emailConnector.absence.subject.invalid");
    }
  }

  /**
   * Checks the zone whenever the reply has one or a date window needs one.
   *
   * @param zone the zone, may be null
   * @param start the first day, may be null
   * @param end the last day, may be null
   * @throws IllegalArgumentException with a message code when the zone is required and
   *           unknown
   */
  private static void validateZone(String zone, LocalDate start, LocalDate end) {
    if (zone != null || start != null || end != null) {
      try {
        ZoneId.of(zone == null ? "" : zone);
      } catch (DateTimeException e) {
        throw new IllegalArgumentException("emailConnector.absence.timeZone.invalid", e);
      }
    }
  }

  /**
   * A string field, null when absent.
   *
   * @param node the object
   * @param field the field
   * @return the value or null
   */
  private static String string(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  /**
   * A date field, null when absent.
   *
   * @param node the object
   * @param field the field
   * @return the value or null
   */
  private static LocalDate date(JsonNode node, String field) {
    String value = string(node, field);
    return value == null ? null : LocalDate.parse(value);
  }
}
