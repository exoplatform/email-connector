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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRule.Action;
import org.exoplatform.emailConnector.model.ServerRule.Condition;
import org.exoplatform.emailConnector.service.rules.sieve.ExoSieveScript.Vacation;

/**
 * eXo's self-describing script: the exact layout, the {@code require} union, the header
 * round trip, the opaque treatment of anything else, and the escaping that keeps every
 * user string inside its own quoted string.
 */
public class ExoSieveScriptTest {

  private static final String HANDLE = "exo-vacation-1727270000";

  /**
   * The layout of a reply with a window, byte for byte: header, require union, the
   * vacation section, the rules marker, CRLF line endings.
   */
  @Test
  public void testLayoutOfAReplyWithAWindow() {
    ExoSieveScript script = ExoSieveScript.empty()
                                          .withVacation(vacation(true,
                                                                 LocalDate.of(2026, 10, 1),
                                                                 LocalDate.of(2026, 10, 15),
                                                                 "Absent",
                                                                 "Back soon"));
    String expected = "# exo-managed-v1: {\"v\":1,\"vacation\":{\"enabled\":true,\"start\":\"2026-10-01\","
        + "\"end\":\"2026-10-15\",\"zone\":\"Europe/Paris\",\"subject\":\"Absent\",\"text\":\"Back soon\","
        + "\"handle\":\"" + HANDLE + "\",\"days\":7},\"rules\":[]}\r\n"
        + "require [\"vacation\", \"date\", \"relational\"];\r\n"
        + "# exo-vacation\r\n"
        + "if allof(currentdate :zone \"+0200\" :value \"ge\" \"date\" \"2026-10-01\",\r\n"
        + "         currentdate :zone \"+0200\" :value \"le\" \"date\" \"2026-10-15\") {\r\n"
        + "  vacation :days 7 :subject \"Absent\" :handle \"" + HANDLE + "\" \"Back soon\";\r\n"
        + "}\r\n"
        + "# exo-rules\r\n";
    assertEquals(expected, script.toScript());
  }

  /**
   * Without a window, no {@code date}/{@code relational} is required and the reply is
   * unconditional; with one boundary, a single test.
   */
  @Test
  public void testRequireIsTheUnionOfWhatIsEmitted() {
    String open = ExoSieveScript.empty().withVacation(vacation(true, null, null, "Away", "Away")).toScript();
    assertTrue(open.contains("require [\"vacation\"];\r\n# exo-vacation\r\nvacation :days 7 :subject \"Away\" :handle"));
    String fromOnly = ExoSieveScript.empty()
                                    .withVacation(vacation(true, LocalDate.of(2026, 10, 1), null, "Away", "Away"))
                                    .toScript();
    assertTrue(fromOnly.contains("require [\"vacation\", \"date\", \"relational\"];"));
    assertTrue(fromOnly.contains("if currentdate :zone \"+0200\" :value \"ge\" \"date\" \"2026-10-01\" {\r\n"));
  }

  /**
   * A disabled reply keeps its text in the header and emits no Sieve at all.
   */
  @Test
  public void testADisabledReplyIsRememberedAndNotEmitted() {
    String text = ExoSieveScript.empty().withVacation(vacation(false, null, null, "Absent", "Back soon")).toScript();
    assertTrue(text.startsWith("# exo-managed-v1: {\"v\":1,\"vacation\":{\"enabled\":false,"));
    assertTrue(text.contains("\"text\":\"Back soon\""));
    assertFalse(text.contains("require"));
    assertFalse(text.contains(ExoSieveScript.VACATION_MARKER));
    assertTrue(text.endsWith("\r\n# exo-rules\r\n"));
  }

  /**
   * One offset per boundary, from the zone at that boundary: a window across the end of
   * summer time has two offsets.
   */
  @Test
  public void testOffsetsFollowTheZoneAtEachBoundary() {
    String text = ExoSieveScript.empty()
                                .withVacation(vacation(true,
                                                       LocalDate.of(2026, 10, 20),
                                                       LocalDate.of(2026, 11, 10),
                                                       "Away",
                                                       "Away"))
                                .toScript();
    assertTrue(text.contains("currentdate :zone \"+0200\" :value \"ge\" \"date\" \"2026-10-20\""));
    assertTrue(text.contains("currentdate :zone \"+0100\" :value \"le\" \"date\" \"2026-11-10\""));
    assertEquals("-0330", ExoSieveScript.offset(java.time.ZoneOffset.ofHoursMinutes(-3, -30)));
  }

  /**
   * Hostile subject and text stay inside their quoted strings: once the strings are
   * removed, the script holds exactly the allowlisted commands and nothing else.
   */
  @Test
  public void testHostileInputCannotEscapeItsString() {
    String hostile = "\"; redirect \"x@evil.example\"; # \\";
    String text = ExoSieveScript.empty()
                                .withVacation(vacation(true, null, null, hostile, hostile + "\nline2 \\\" } discard;"))
                                .toScript();
    String code = SieveTokenScan.withoutComments(text).replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
    assertEquals("require [\"\"];\r\n \nvacation :days 7 :subject \"\" :handle \"\" \"\";\r\n \n",
                 code.substring(code.indexOf("require")));
    assertTrue(text.contains(":subject \"\\\"; redirect \\\"x@evil.example\\\"; # \\\\\""));
    assertTrue(text.contains("line2 \\\\\\\" } discard;\";\r\n"));
  }

  /**
   * The subject is mandatory: without it Pigeonhole prefixes the reply's subject with
   * {@code Auto: }.
   */
  @Test
  public void testSubjectIsMandatory() {
    assertCode("emailConnector.absence.subject.invalid", () -> vacation(true, null, null, null, "t"));
    assertCode("emailConnector.absence.subject.invalid", () -> vacation(true, null, null, " ", "t"));
  }

  /**
   * Stalwart un-escapes {@code \\} a second time: where the server advertises
   * encoded-character, a backslash is written {@code ${unicode:5C}}, a dollar
   * {@code ${unicode:24}} so a typed {@code ${…}} stays text, and the extension is
   * required only when a string needed it. Elsewhere, RFC escaping.
   */
  @Test
  public void testBackslashEncodingStrategies() {
    ExoSieveScript script = ExoSieveScript.empty().withVacation(vacation(true, null, null, "Dir C:\\temp", "Cost: ${hex:41} \\ \"x\""));
    String encoded = script.toScript(SieveStringEncoding.ENCODED_CHARACTER);
    assertTrue(encoded.contains("require [\"vacation\", \"encoded-character\"];"));
    assertTrue(encoded.contains(":subject \"Dir C:${unicode:5C}temp\""));
    assertTrue(encoded.contains("\"Cost: ${unicode:24}{hex:41} ${unicode:5C} \\\"x\\\"\";"));
    assertFalse(encoded.substring(encoded.indexOf("# exo-vacation")).contains("\\\\"));
    String escaped = script.toScript(SieveStringEncoding.ESCAPED);
    assertEquals(escaped, script.toScript());
    assertTrue(escaped.contains(":subject \"Dir C:\\\\temp\""));
    assertFalse(escaped.contains("encoded-character"));
    String plain = ExoSieveScript.empty().withVacation(vacation(true, null, null, "Away", "t")).toScript(SieveStringEncoding.ENCODED_CHARACTER);
    assertFalse(plain.contains("encoded-character"));
    assertEquals(script, ExoSieveScript.parse(encoded).orElseThrow());
    assertEquals(SieveStringEncoding.ENCODED_CHARACTER,
                 SieveStringEncoding.forCapabilities(ManageSieveCapabilities.fromLines(java.util.List.of(java.util.List.of("SIEVE", "vacation encoded-character")))));
    assertEquals(SieveStringEncoding.ESCAPED,
                 SieveStringEncoding.forCapabilities(ManageSieveCapabilities.fromLines(java.util.List.of(java.util.List.of("SIEVE", "vacation")))));
  }

  /**
   * The text keeps its line breaks, normalised to CRLF; the subject may not have any.
   */
  @Test
  public void testLineBreaks() {
    assertEquals("a\r\nb\r\nc\r\nd", vacation(true, null, null, "Away", "a\nb\r\nc\rd").text());
    assertThrows(IllegalArgumentException.class, () -> vacation(true, null, null, "a\r\nBcc: x@evil.example", "t"));
    assertThrows(IllegalArgumentException.class, () -> vacation(true, null, null, "a\nb", "t"));
  }

  /**
   * Every bound answers the message code the REST layer returns.
   */
  @Test
  public void testValidation() {
    assertCode("emailConnector.absence.text.invalid", () -> vacation(true, null, null, "Away", " "));
    assertCode("emailConnector.absence.text.invalid", () -> vacation(true, null, null, "Away", "x".repeat(4001)));
    assertCode("emailConnector.absence.text.invalid", () -> vacation(true, null, null, "Away", "a\0b"));
    assertCode("emailConnector.absence.subject.invalid", () -> vacation(true, null, null, "x".repeat(201), "t"));
    assertCode("emailConnector.absence.subject.invalid", () -> vacation(true, null, null, "a\0", "t"));
    assertCode("emailConnector.absence.window.invalid",
               () -> vacation(true, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1), "Away", "t"));
    assertCode("emailConnector.absence.handle.invalid",
               () -> new Vacation(true, null, null, null, "Away", "t", "exo\" :from \"x", 7));
    assertCode("emailConnector.absence.days.invalid", () -> new Vacation(true, null, null, null, "Away", "t", HANDLE, 0));
    assertCode("emailConnector.absence.timeZone.invalid",
               () -> new Vacation(true, LocalDate.of(2026, 10, 1), null, "Mars/Olympus", "Away", "t", HANDLE, 7));
    assertCode("emailConnector.absence.timeZone.invalid",
               () -> new Vacation(true, LocalDate.of(2026, 10, 1), null, null, "Away", "t", HANDLE, 7));
  }

  /**
   * text → model → text is byte-equal, and the model survives.
   */
  @Test
  public void testHeaderRoundTrip() {
    ExoSieveScript script = ExoSieveScript.empty()
                                          .withVacation(vacation(true,
                                                                 LocalDate.of(2026, 10, 1),
                                                                 LocalDate.of(2026, 10, 15),
                                                                 "Absent — \"en congés\"",
                                                                 "Retour le 15\n\\o/"));
    String text = script.toScript();
    ExoSieveScript parsed = ExoSieveScript.parse(text).orElseThrow();
    assertEquals(script, parsed);
    assertEquals(text, parsed.toScript());
    assertEquals(script.getVacation(), parsed.getVacation());
  }

  /**
   * A top-level key eXo does not know is written back untouched, after the reserved
   * ones.
   */
  @Test
  public void testUnknownKeysAreRoundTripped() {
    String text = "# exo-managed-v1: {\"v\":1,\"future\":{\"x\":[1,\"y\"]},\"rules\":[]}\r\n# exo-rules\r\n";
    String regenerated = ExoSieveScript.parse(text).orElseThrow().toScript();
    assertEquals("# exo-managed-v1: {\"v\":1,\"rules\":[],\"future\":{\"x\":[1,\"y\"]}}\r\n# exo-rules\r\n", regenerated);
  }

  /**
   * Anything that is not eXo's header, version 1, valid, is opaque: answered empty.
   */
  @Test
  public void testForeignOrUnreadableContentIsOpaque() {
    assertEquals(Optional.empty(), ExoSieveScript.parse(null));
    assertEquals(Optional.empty(), ExoSieveScript.parse("require [\"fileinto\"];\r\nfileinto \"x\";\r\n"));
    assertEquals(Optional.empty(), ExoSieveScript.parse("\n# exo-managed-v1: {\"v\":1}\n"));
    assertEquals(Optional.empty(), ExoSieveScript.parse("# exo-managed-v1: {\"v\":2,\"rules\":[]}\r\n"));
    assertEquals(Optional.empty(), ExoSieveScript.parse("# exo-managed-v1: {\"v\":1,\r\n"));
    assertEquals(Optional.empty(), ExoSieveScript.parse("# exo-managed-v1: {\"v\":1,\"rules\":{}}\r\n"));
    assertEquals(Optional.empty(),
                 ExoSieveScript.parse("# exo-managed-v1: {\"v\":1,\"vacation\":{\"enabled\":true,\"days\":7,"
                     + "\"text\":\"t\",\"handle\":\"bad handle\"}}\r\n"));
    assertTrue(ExoSieveScript.parse("# exo-managed-v1: {\"v\":1}").isPresent());
  }

  /**
   * The reply and two rules in one script, byte for byte: the {@code require} line is the
   * union of both sections in its fixed order, the vacation section comes first, then the
   * rules marker and each enabled rule -- flags, then the filing, then {@code stop}.
   */
  @Test
  public void testLayoutOfAReplyAndTwoRules() {
    ExoSieveScript script = ExoSieveScript.empty()
                                          .withVacation(vacation(true,
                                                                 LocalDate.of(2026, 10, 1),
                                                                 LocalDate.of(2026, 10, 15),
                                                                 "Absent",
                                                                 "Back soon"))
                                          .withRules(List.of(acmeInvoices(), listsRead()));
    String expected = "# exo-managed-v1: {\"v\":1,\"vacation\":{\"enabled\":true,\"start\":\"2026-10-01\","
        + "\"end\":\"2026-10-15\",\"zone\":\"Europe/Paris\",\"subject\":\"Absent\",\"text\":\"Back soon\","
        + "\"handle\":\"" + HANDLE + "\",\"days\":7},\"rules\":["
        + "{\"id\":\"1\",\"name\":\"Acme invoices\",\"enabled\":true,\"match\":\"ALL\",\"conditions\":["
        + "{\"field\":\"FROM\",\"operator\":\"MATCHES_DOMAIN\",\"value\":\"acme.com\"},"
        + "{\"field\":\"SUBJECT\",\"operator\":\"CONTAINS\",\"value\":\"invoice\"}],"
        + "\"actions\":[{\"type\":\"STAR\"},{\"type\":\"MOVE_TO_FOLDER\",\"folderKey\":\"CUSTOM:12\",\"folder\":\"Accounting\"}],"
        + "\"stop\":true},"
        + "{\"id\":\"2\",\"name\":\"Lists read\",\"enabled\":true,\"match\":\"ALL\",\"conditions\":["
        + "{\"field\":\"IS_LIST\",\"operator\":\"IS_TRUE\"}],\"actions\":[{\"type\":\"MARK_READ\"}],\"stop\":false}]}\r\n"
        + "require [\"vacation\", \"date\", \"relational\", \"fileinto\", \"imap4flags\"];\r\n"
        + "# exo-vacation\r\n"
        + "if allof(currentdate :zone \"+0200\" :value \"ge\" \"date\" \"2026-10-01\",\r\n"
        + "         currentdate :zone \"+0200\" :value \"le\" \"date\" \"2026-10-15\") {\r\n"
        + "  vacation :days 7 :subject \"Absent\" :handle \"" + HANDLE + "\" \"Back soon\";\r\n"
        + "}\r\n"
        + "# exo-rules\r\n"
        + "# exo-rule 1\r\n"
        + "if allof(anyof(address :domain :is \"from\" \"acme.com\", address :domain :matches \"from\" \"*.acme.com\"), "
        + "header :contains \"subject\" \"invoice\") {\r\n"
        + "  addflag \"\\\\Flagged\";\r\n"
        + "  fileinto \"Accounting\";\r\n"
        + "  stop;\r\n"
        + "}\r\n"
        + "# exo-rule 2\r\n"
        + "if anyof(exists \"list-id\", exists \"list-post\", exists \"list-unsubscribe\") {\r\n"
        + "  addflag \"\\\\Seen\";\r\n"
        + "}\r\n";
    assertEquals(expected, script.toScript());
  }

  /**
   * Rules only: {@code fileinto} and {@code imap4flags} alone are required, the vacation
   * section is absent, the rules marker stays where it is.
   */
  @Test
  public void testRulesWithoutAReply() {
    String text = ExoSieveScript.empty().withRules(List.of(acmeInvoices())).toScript();
    String body = text.substring(text.indexOf("\r\n") + 2);
    assertTrue(body.startsWith("require [\"fileinto\", \"imap4flags\"];\r\n# exo-rules\r\n# exo-rule 1\r\n"), body);
    assertFalse(body.contains(ExoSieveScript.VACATION_MARKER));
    String flagsOnly = ExoSieveScript.empty().withRules(List.of(listsRead())).toScript();
    assertTrue(flagsOnly.contains("require [\"imap4flags\"];\r\n"));
  }

  /**
   * A disabled rule stays in the header and emits nothing: no block, no extension.
   */
  @Test
  public void testADisabledRuleIsRememberedAndNotEmitted() {
    ServerRule disabled = new ServerRule("1", "Off", false, true, acmeInvoices().conditions(), acmeInvoices().actions(), true);
    ExoSieveScript script = ExoSieveScript.empty().withRules(List.of(disabled));
    String text = script.toScript();
    assertTrue(text.contains("\"name\":\"Off\",\"enabled\":false"));
    assertFalse(text.contains("require"));
    assertFalse(text.contains("fileinto \""));
    assertTrue(text.endsWith("\r\n# exo-rules\r\n"));
    assertFalse(script.emitsRules());
    assertFalse(script.filesOrStops());
  }

  /**
   * Whether the script files or stops follows what its enabled rules do: a rule that only
   * flags neither files nor stops, a filing action or a {@code stop} does; the reply
   * never does.
   */
  @Test
  public void testFilesOrStopsFollowsTheRules() {
    assertFalse(ExoSieveScript.empty().withVacation(vacation(true, null, null, "Away", "t")).filesOrStops());
    assertFalse(ExoSieveScript.empty().withRules(List.of(listsRead())).filesOrStops());
    assertTrue(ExoSieveScript.empty().withRules(List.of(listsRead(), acmeInvoices())).filesOrStops());
    ServerRule stopOnly = new ServerRule("3", "Stop", true, true, listsRead().conditions(), listsRead().actions(), true);
    assertTrue(ExoSieveScript.empty().withRules(List.of(stopOnly)).filesOrStops());
  }

  /**
   * text → model → text is byte-equal with rules and a reply, and the reply's write keeps
   * the rules as they were.
   */
  @Test
  public void testRulesRoundTripAndSurviveAReplyEdit() {
    ExoSieveScript script = ExoSieveScript.empty()
                                          .withVacation(vacation(true, null, null, "Away", "t"))
                                          .withRules(List.of(acmeInvoices(), listsRead()));
    String text = script.toScript(SieveStringEncoding.ENCODED_CHARACTER);
    ExoSieveScript parsed = ExoSieveScript.parse(text).orElseThrow();
    assertEquals(script, parsed);
    assertEquals(text, parsed.toScript(SieveStringEncoding.ENCODED_CHARACTER));
    String edited = parsed.withVacation(vacation(false, null, null, "Away", "t")).toScript(SieveStringEncoding.ENCODED_CHARACTER);
    String rulesBefore = text.substring(text.indexOf(ExoSieveScript.RULES_MARKER));
    assertEquals(rulesBefore, edited.substring(edited.indexOf(ExoSieveScript.RULES_MARKER)));
  }

  /**
   * A rules entry eXo would not write makes the header unreadable as eXo's: answered
   * empty, never interpreted.
   */
  @Test
  public void testAnInvalidRuleMakesTheHeaderOpaque() {
    String prefix = "# exo-managed-v1: {\"v\":1,\"rules\":[";
    assertEquals(Optional.empty(), ExoSieveScript.parse(prefix + "{\"id\":1}]}\r\n"));
    String valid = "{\"id\":\"1\",\"name\":\"n\",\"enabled\":true,\"match\":\"ALL\",\"conditions\":[{\"field\":\"IS_LIST\","
        + "\"operator\":\"IS_TRUE\"}],\"actions\":[%s],\"stop\":false}";
    assertTrue(ExoSieveScript.parse(prefix + String.format(valid, "{\"type\":\"STAR\"}") + "]}\r\n").isPresent());
    for (String action : List.of("{\"type\":\"REDIRECT\",\"folder\":\"x@evil.example\"}",
                                 "{\"type\":\"MOVE_TO_FOLDER\",\"folder\":\"a\\nb\"}",
                                 "{\"type\":\"TAG\",\"keyword\":\"\\\\Deleted\"}",
                                 "{\"type\":\"STAR\"},{\"type\":\"STAR\"}")) {
      assertEquals(Optional.empty(), ExoSieveScript.parse(prefix + String.format(valid, action) + "]}\r\n"), action);
    }
    String twice = String.format(valid, "{\"type\":\"STAR\"}");
    assertEquals(Optional.empty(), ExoSieveScript.parse(prefix + twice + "," + twice + "]}\r\n"));
  }

  /**
   * Every rule the model holds has its own reference.
   */
  @Test
  public void testWithRulesNeedsUniqueReferences() {
    assertThrows(IllegalArgumentException.class, () -> ExoSieveScript.empty().withRules(List.of(acmeInvoices().withRef(null))));
    assertThrows(IllegalArgumentException.class,
                 () -> ExoSieveScript.empty().withRules(List.of(acmeInvoices(), listsRead().withRef("1"))));
  }

  /**
   * Mail from acme.com about an invoice: starred, filed into Accounting, the rules after it
   * skipped.
   *
   * @return the rule, reference 1
   */
  static ServerRule acmeInvoices() {
    return new ServerRule("1",
                          "Acme invoices",
                          true,
                          true,
                          List.of(new Condition("FROM", "MATCHES_DOMAIN", null, "acme.com"),
                                  new Condition("SUBJECT", "CONTAINS", null, "invoice")),
                          List.of(new Action("STAR", null, null, null), new Action("MOVE_TO_FOLDER", "CUSTOM:12", "Accounting", null)),
                          true);
  }

  /**
   * Mail from a list: marked read.
   *
   * @return the rule, reference 2
   */
  static ServerRule listsRead() {
    return new ServerRule("2",
                          "Lists read",
                          true,
                          true,
                          List.of(new Condition("IS_LIST", "IS_TRUE", null, null)),
                          List.of(new Action("MARK_READ", null, null, null)),
                          false);
  }

  /**
   * Whether a vacation action is emitted follows the enabled flag.
   */
  @Test
  public void testEmitsVacation() {
    assertFalse(ExoSieveScript.empty().emitsVacation());
    assertFalse(ExoSieveScript.empty().withVacation(vacation(false, null, null, "Away", "t")).emitsVacation());
    assertTrue(ExoSieveScript.empty().withVacation(vacation(true, null, null, "Away", "t")).emitsVacation());
  }

  /**
   * The hash is the SHA-256 of the generated text, and moves with the text.
   */
  @Test
  public void testHash() {
    assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ExoSieveScript.sha256(""));
    ExoSieveScript one = ExoSieveScript.empty().withVacation(vacation(true, null, null, "Away", "one"));
    ExoSieveScript two = ExoSieveScript.empty().withVacation(vacation(true, null, null, "Away", "two"));
    assertNotEquals(ExoSieveScript.sha256(one.toScript()), ExoSieveScript.sha256(two.toScript()));
  }

  /**
   * A reply in Europe/Paris with the fixed handle and a 7-day interval.
   *
   * @param enabled whether it is on
   * @param start the first day
   * @param end the last day
   * @param subject the subject
   * @param text the text
   * @return the reply
   */
  private static Vacation vacation(boolean enabled, LocalDate start, LocalDate end, String subject, String text) {
    return new Vacation(enabled, start, end, "Europe/Paris", subject, text, HANDLE, 7);
  }

  /**
   * Asserts that building a reply fails with a message code.
   *
   * @param code the expected code
   * @param builder the construction
   */
  private static void assertCode(String code, Runnable builder) {
    assertEquals(code, assertThrows(IllegalArgumentException.class, builder::run).getMessage());
  }
}
