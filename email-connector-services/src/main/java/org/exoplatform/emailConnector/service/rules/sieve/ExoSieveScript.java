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
package org.exoplatform.emailConnector.service.rules.sieve;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * eXo's own Sieve script, {@value #SCRIPT_NAME}: a self-describing model carried in the
 * script's first line, and the Sieve text generated from it.
 * <p>
 * The first line is {@code # exo-managed-v1: {"v":1,"vacation":{…},"rules":[…]}} — a
 * Sieve comment holding the model as one line of JSON. eXo reads its own script back
 * from that line, never from the Sieve below it: the server stays the only store, and
 * free-form Sieve is never parsed. The two reserved keys are {@code vacation} and
 * {@code rules}; any other top-level key is kept and written back untouched, so a
 * later feature needs no header version.
 * <p>
 * The generated Sieve is sectioned, in a fixed order: the {@code require} line (the
 * union of what the emitted sections need), the {@code # exo-vacation} section, then
 * the {@code # exo-rules} section. The vacation section comes first because
 * {@code vacation} neither files nor stops (RFC 5230 §4.6): no later {@code stop} can
 * skip it, and a later {@code fileinto} still applies.
 * <p>
 * The vocabulary is a closed allowlist: {@code require}, {@code if allof(currentdate …)}
 * and {@code vacation} with {@code :days}, {@code :subject} and {@code :handle}. Every
 * user string becomes a Sieve quoted string with {@code \} and {@code "} escaped, so it
 * cannot close its string and become a command. The rules section is filled by the
 * filters eXip; this class refuses to serialise rules it has no generator for.
 * <p>
 * Immutable.
 */
public final class ExoSieveScript {

  /** The name of eXo's own script on the server. */
  public static final String        SCRIPT_NAME       = "exo-rules";

  /** The name of the wrapper eXo activates when another script must keep running. */
  public static final String        WRAPPER_NAME      = "exo-main";

  /** The first-line marker of eXo's script; the JSON model follows it. */
  public static final String        HEADER_PREFIX     = "# exo-managed-v1: ";

  /** The model version the header carries under {@value #KEY_VERSION}. */
  public static final int           HEADER_VERSION    = 1;

  /** The header key of the model version. */
  public static final String        KEY_VERSION       = "v";

  /** The reserved header key of the automatic reply. */
  public static final String        KEY_VACATION      = "vacation";

  /** The reserved header key of the server rules. */
  public static final String        KEY_RULES         = "rules";

  /** The comment opening the vacation section. */
  public static final String        VACATION_MARKER   = "# exo-vacation";

  /** The comment opening the rules section. */
  public static final String        RULES_MARKER      = "# exo-rules";

  /**
   * The user {@code SettingService} key under which the caller keeps {@link #hash()} of
   * the text it last wrote: one neutral entry, checked by the automatic reply and the
   * server rules alike, since both write this one script.
   */
  public static final String        HASH_SETTING_KEY  = "emailSieveScript";

  /** Sieve lines end with CRLF (RFC 5228 §2.2). */
  static final String               EOL               = "\r\n";

  private static final JsonMapper   JSON              = JsonMapper.builder().build();

  /** The automatic reply, or null when the script has none. */
  private final Vacation            vacation;

  /** The rules, as the filters eXip writes them; kept opaque here. */
  private final ArrayNode           rules;

  /** Every other top-level header key, in its order, written back untouched. */
  private final Map<String, JsonNode> otherKeys;

  /**
   * A script model.
   *
   * @param vacation the automatic reply, possibly null
   * @param rules the rules, possibly null for none
   * @param otherKeys the unknown top-level keys, possibly null
   */
  private ExoSieveScript(Vacation vacation, ArrayNode rules, Map<String, JsonNode> otherKeys) {
    this.vacation = vacation;
    this.rules = rules == null ? JsonNodeFactory.instance.arrayNode() : rules.deepCopy();
    this.otherKeys = otherKeys == null ? Map.of() : new LinkedHashMap<>(otherKeys);
  }

  /**
   * A script with nothing in it: no automatic reply, no rule.
   *
   * @return the empty script
   */
  public static ExoSieveScript empty() {
    return new ExoSieveScript(null, null, null);
  }

  /**
   * This script with another automatic reply, rules and unknown keys unchanged.
   *
   * @param newVacation the automatic reply, null to remove it from the model
   * @return the new script
   */
  public ExoSieveScript withVacation(Vacation newVacation) {
    return new ExoSieveScript(newVacation, rules, otherKeys);
  }

  /**
   * The automatic reply the model holds, enabled or not.
   *
   * @return the reply, or empty
   */
  public Optional<Vacation> getVacation() {
    return Optional.ofNullable(vacation);
  }

  /**
   * The rules the model holds, as JSON; a copy.
   *
   * @return the rules, never null
   */
  public ArrayNode getRules() {
    return rules.deepCopy();
  }

  /**
   * Whether the generated script contains a {@code vacation} action, which is what makes
   * the RFC 5230 "once per script run" rule matter next to another script.
   *
   * @return true when the vacation section is emitted
   */
  public boolean emitsVacation() {
    return vacation != null && vacation.enabled();
  }

  /**
   * Whether the generated script may file, stop, discard, reject or redirect a mail. The
   * generator knows what it emits: the vacation section does none of these, and any
   * rule is assumed to, until the filters eXip's rules generator answers per rule.
   *
   * @return true when a rule section is emitted
   */
  public boolean filesOrStops() {
    return !rules.isEmpty();
  }

  /**
   * Reads eXo's model back from a script's text.
   * <p>
   * Only the first line is read. A script whose first line is not eXo's header, whose
   * JSON does not parse, whose version is not {@value #HEADER_VERSION} or whose reply
   * does not validate is not eXo's to interpret: it is answered empty and treated as
   * opaque, never parsed further.
   *
   * @param text the script text, as {@code GETSCRIPT} returned it
   * @return the model, or empty for foreign or unreadable content
   */
  public static Optional<ExoSieveScript> parse(String text) {
    if (text == null || !text.startsWith(HEADER_PREFIX)) {
      return Optional.empty();
    }
    int end = text.indexOf('\n');
    String json = text.substring(HEADER_PREFIX.length(), end < 0 ? text.length() : end);
    if (json.endsWith("\r")) {
      json = json.substring(0, json.length() - 1);
    }
    try {
      JsonNode root = JSON.readTree(json);
      if (!(root instanceof ObjectNode header) || !header.path(KEY_VERSION).isInt()
          || header.path(KEY_VERSION).intValue() != HEADER_VERSION) {
        return Optional.empty();
      }
      Vacation vacation = null;
      ArrayNode rules = null;
      Map<String, JsonNode> other = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> entry : header.properties()) {
        switch (entry.getKey()) {
        case KEY_VERSION -> {
          // Already checked.
        }
        case KEY_VACATION -> vacation = entry.getValue().isNull() ? null : Vacation.fromJson(entry.getValue());
        case KEY_RULES -> {
          if (!(entry.getValue() instanceof ArrayNode array)) {
            return Optional.empty();
          }
          rules = array;
        }
        default -> other.put(entry.getKey(), entry.getValue());
        }
      }
      return Optional.of(new ExoSieveScript(vacation, rules, other));
    } catch (JacksonException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /**
   * Generates the script text with RFC 5228 string escaping.
   *
   * @return the Sieve text, CRLF line endings
   * @throws IllegalStateException when the model holds rules, which only the filters
   *           eXip's generator can serialise
   */
  public String toScript() {
    return toScript(SieveStringEncoding.ESCAPED);
  }

  /**
   * Generates the script text: header, {@code require}, vacation section, rules marker,
   * user strings encoded with the server's strategy.
   *
   * @param encoding how user strings become quoted strings on this server
   * @return the Sieve text, CRLF line endings
   * @throws IllegalStateException when the model holds rules, which only the filters
   *           eXip's generator can serialise
   */
  public String toScript(SieveStringEncoding encoding) {
    if (!rules.isEmpty()) {
      throw new IllegalStateException("The model holds server rules and no rules generator is available");
    }
    StringBuilder script = new StringBuilder();
    script.append(HEADER_PREFIX).append(headerJson()).append(EOL);
    List<String> require = new ArrayList<>();
    if (emitsVacation()) {
      require.add("vacation");
      if (vacation.start() != null || vacation.end() != null) {
        require.add("date");
        require.add("relational");
      }
      if (encoding.needsExtension(vacation.subject()) || encoding.needsExtension(vacation.text())) {
        require.add(SieveStringEncoding.EXTENSION);
      }
    }
    if (!require.isEmpty()) {
      script.append("require [");
      for (int i = 0; i < require.size(); i++) {
        script.append(i == 0 ? "" : ", ").append(quote(require.get(i)));
      }
      script.append("];").append(EOL);
    }
    if (emitsVacation()) {
      script.append(VACATION_MARKER).append(EOL);
      appendVacation(script, encoding);
    }
    script.append(RULES_MARKER).append(EOL);
    return script.toString();
  }

  /**
   * The SHA-256 of the generated text, the value eXo stores to notice an edit made
   * outside eXo.
   *
   * @return the lower-case hex digest
   */
  public String hash() {
    return sha256(toScript());
  }

  /**
   * The SHA-256 of the text generated for a server's encoding.
   *
   * @param encoding the server's string encoding
   * @return the lower-case hex digest
   */
  public String hash(SieveStringEncoding encoding) {
    return sha256(toScript(encoding));
  }

  /**
   * The SHA-256 of a script text, as UTF-8.
   *
   * @param text the text
   * @return the lower-case hex digest
   */
  public static String sha256(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * Encodes a value as a Sieve quoted string (RFC 5228 §2.4.2): backslash and double
   * quote escaped, nothing else changed, so the value always ends inside its own string.
   *
   * @param value the value
   * @return the quoted string, quotes included
   */
  public static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /**
   * The header's JSON: version, reply, rules, then every other key in its order.
   *
   * @return the one-line JSON
   */
  private String headerJson() {
    ObjectNode header = JsonNodeFactory.instance.objectNode();
    header.put(KEY_VERSION, HEADER_VERSION);
    if (vacation != null) {
      header.set(KEY_VACATION, vacation.toJson());
    }
    header.set(KEY_RULES, rules.deepCopy());
    for (Map.Entry<String, JsonNode> entry : otherKeys.entrySet()) {
      header.set(entry.getKey(), entry.getValue().deepCopy());
    }
    return JSON.writeValueAsString(header);
  }

  /**
   * Appends the vacation action, inside a date window when the reply has one. The
   * {@code :subject} and the {@code :handle} are always emitted: without a subject
   * Pigeonhole prefixes the sender's with {@code Auto: }, and without a handle an edit of
   * the text replies again to everyone (V0 spike, rows 7 and 15).
   *
   * @param script the script being built
   * @param encoding how user strings become quoted strings
   */
  private void appendVacation(StringBuilder script, SieveStringEncoding encoding) {
    List<String> tests = new ArrayList<>();
    ZoneId zone = vacation.zoneId();
    if (vacation.start() != null) {
      ZoneOffset offset = vacation.start().atStartOfDay(zone).getOffset();
      tests.add("currentdate :zone " + quote(offset(offset)) + " :value \"ge\" \"date\" "
          + quote(vacation.start().toString()));
    }
    if (vacation.end() != null) {
      ZoneOffset offset = vacation.end().atTime(LocalTime.MAX).atZone(zone).getOffset();
      tests.add("currentdate :zone " + quote(offset(offset)) + " :value \"le\" \"date\" "
          + quote(vacation.end().toString()));
    }
    StringBuilder action = new StringBuilder("vacation :days ").append(vacation.days());
    action.append(" :subject ").append(encoding.quote(vacation.subject()));
    action.append(" :handle ").append(quote(vacation.handle())).append(' ').append(encoding.quote(vacation.text())).append(';');
    if (tests.isEmpty()) {
      script.append(action).append(EOL);
      return;
    }
    if (tests.size() == 1) {
      script.append("if ").append(tests.get(0)).append(" {").append(EOL);
    } else {
      script.append("if allof(").append(tests.get(0)).append(',').append(EOL);
      script.append("         ").append(tests.get(1)).append(") {").append(EOL);
    }
    script.append("  ").append(action).append(EOL).append('}').append(EOL);
  }

  /**
   * Formats an offset as RFC 5260 §4.1 wants it: sign and four digits, seconds dropped.
   *
   * @param offset the offset
   * @return e.g. {@code +0200}
   */
  static String offset(ZoneOffset offset) {
    int total = offset.getTotalSeconds();
    char sign = total < 0 ? '-' : '+';
    int minutes = Math.abs(total) / 60;
    return String.format("%c%02d%02d", sign, minutes / 60, minutes % 60);
  }

  /**
   * Structural equality on the model.
   *
   * @param other the other object
   * @return true when both models generate the same header
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof ExoSieveScript script && headerJson().equals(script.headerJson());
  }

  /**
   * Consistent with {@link #equals(Object)}.
   *
   * @return the hash code of the header
   */
  @Override
  public int hashCode() {
    return headerJson().hashCode();
  }

  /**
   * The automatic reply eXo writes, validated so that nothing the user typed can escape
   * its quoted string or add a header to the reply.
   * <p>
   * Bounds: the subject is one line of at most {@value #MAX_SUBJECT_LENGTH} characters,
   * the text at most {@value #MAX_TEXT_LENGTH}, no NUL anywhere; the handle is a token
   * of letters, digits, dots, underscores and dashes; the window's days are the user's
   * own calendar days in {@code zone}, one UTC offset computed per boundary so a window
   * across a DST change stays exact.
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
  public record Vacation(boolean enabled,
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

    private static final Pattern HANDLE             = Pattern.compile("[A-Za-z0-9._-]{1,64}");

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
    public Vacation {
      if (text == null || text.isBlank() || text.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("emailConnector.absence.text.invalid");
      }
      text = storedText(text);
      if (text.length() > MAX_TEXT_LENGTH) {
        throw new IllegalArgumentException("emailConnector.absence.text.invalid");
      }
      if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH || subject.indexOf('\r') >= 0
          || subject.indexOf('\n') >= 0 || subject.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("emailConnector.absence.subject.invalid");
      }
      if (handle == null || !HANDLE.matcher(handle).matches()) {
        throw new IllegalArgumentException("emailConnector.absence.handle.invalid");
      }
      if (days < 1 || days > MAX_DAYS) {
        throw new IllegalArgumentException("emailConnector.absence.days.invalid");
      }
      if (start != null && end != null && start.isAfter(end)) {
        throw new IllegalArgumentException("emailConnector.absence.window.invalid");
      }
      if (zone != null || start != null || end != null) {
        try {
          ZoneId.of(zone == null ? "" : zone);
        } catch (DateTimeException e) {
          throw new IllegalArgumentException("emailConnector.absence.timeZone.invalid", e);
        }
      }
    }

    /**
     * A text as the header stores it: every line break CRLF. The length bound applies to
     * this form, so what is accepted on the way in is also read back from the header.
     *
     * @param text the text, any line breaks
     * @return the text with CRLF line breaks
     */
    public static String storedText(String text) {
      return text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", EOL);
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
    ObjectNode toJson() {
      ObjectNode node = JsonNodeFactory.instance.objectNode();
      node.put("enabled", enabled);
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
    static Vacation fromJson(JsonNode node) {
      if (!node.isObject() || !node.path("enabled").isBoolean() || !node.path("days").isInt()) {
        throw new IllegalArgumentException("emailConnector.absence.header.invalid");
      }
      try {
        return new Vacation(node.path("enabled").booleanValue(),
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
}
