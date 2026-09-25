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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRule.Action;
import org.exoplatform.emailConnector.model.ServerRule.Condition;

/**
 * The rules generator: one test per condition and action of the vocabulary, the
 * {@code :matches} escaping, the order inside a block, the extensions a section requires,
 * and the allowlist under hostile input.
 */
public class SieveRulesSectionTest {

  /** A quoted string, escapes included. */
  private static final Pattern QUOTED = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

  /** A bare word of Sieve code. */
  private static final Pattern WORD   = Pattern.compile(":?[A-Za-z][A-Za-z0-9_-]*");

  /** Every word the generator may write outside a string. */
  private static final Set<String> ALLOWED = Set.of("if", "allof", "anyof", "not", "address", "header", "exists", "size",
                                                    ":contains", ":is", ":matches", ":domain", ":over", ":under", "addflag",
                                                    "fileinto", "stop", "K");

  /**
   * Each condition becomes the test the plan maps it to, on the headers it names.
   */
  @Test
  public void testConditions() {
    assertEquals("address :contains \"from\" \"bob\"", test("FROM", "CONTAINS", null, "bob"));
    assertEquals("not address :contains \"to\" \"bob\"", test("TO", "NOT_CONTAINS", null, "bob"));
    assertEquals("address :is \"cc\" \"bob@x.org\"", test("CC", "EQUALS", null, "bob@x.org"));
    assertEquals("address :matches [\"to\", \"cc\"] \"bob*\"", test("ANY_RECIPIENT", "STARTS_WITH", null, "bob"));
    assertEquals("address :matches \"from\" \"*@x.org\"", test("FROM", "ENDS_WITH", null, "@x.org"));
    assertEquals("anyof(address :domain :is \"from\" \"acme.com\", address :domain :matches \"from\" \"*.acme.com\")",
                 test("FROM", "MATCHES_DOMAIN", null, "@ACME.com"));
    assertEquals("header :contains \"subject\" \"invoice\"", test("SUBJECT", "CONTAINS", null, "invoice"));
    assertEquals("header :is \"x-priority\" \"1\"", test("HEADER", "EQUALS", "X-Priority", "1"));
    assertEquals("size :over 100K", test("MESSAGE_SIZE", "GT", null, "100"));
    assertEquals("size :under 20K", test("MESSAGE_SIZE", "LT", null, "20"));
    assertEquals("anyof(exists \"list-id\", exists \"list-post\", exists \"list-unsubscribe\")", test("IS_LIST", "IS_TRUE", null, null));
    assertEquals("not anyof(exists \"list-id\", exists \"list-post\", exists \"list-unsubscribe\")",
                 test("IS_LIST", "IS_FALSE", null, null));
    assertEquals("allof(exists \"auto-submitted\", not header :is \"auto-submitted\" \"no\")",
                 test("IS_AUTOMATED", "IS_TRUE", null, null));
    assertEquals("not allof(exists \"auto-submitted\", not header :is \"auto-submitted\" \"no\")",
                 test("IS_AUTOMATED", "IS_FALSE", null, null));
  }

  /**
   * A {@code :matches} value matches as typed: its {@code *}, {@code ?} and backslash are
   * escaped for the pattern, then the backslashes for the string.
   */
  @Test
  public void testMatchesValuesAreLiteral() {
    assertEquals("header :matches \"subject\" \"50% off\\\\*\\\\?*\"", test("SUBJECT", "STARTS_WITH", null, "50% off*?"));
    assertEquals("\\*a\\\\b\\?", SieveRulesSection.pattern("*a\\b?"));
    SieveRulesSection.Generated encoded = generate(SieveStringEncoding.ENCODED_CHARACTER,
                                                   rule("1", List.of(new Condition("SUBJECT", "ENDS_WITH", null, "a*")),
                                                        List.of(new Action("STAR", null, null, null)), false));
    assertTrue(encoded.text().contains("header :matches \"subject\" \"*a${unicode:5C}*\""), encoded.text());
    assertTrue(encoded.extensions().contains(SieveStringEncoding.EXTENSION));
  }

  /**
   * Allof or anyof over several conditions; flags first in the block, then the filing,
   * then {@code stop}, last.
   */
  @Test
  public void testBlockOrder() {
    ServerRule rule = new ServerRule("7",
                                     "All",
                                     true,
                                     false,
                                     List.of(new Condition("FROM", "CONTAINS", null, "a"), new Condition("TO", "CONTAINS", null, "b")),
                                     List.of(new Action("DELETE", "TRASH", "Deleted Items", null),
                                             new Action("TAG", null, null, "exo-filter-42"),
                                             new Action("STAR", null, null, null),
                                             new Action("MARK_READ", null, null, null)),
                                     true).validated();
    String text = SieveRulesSection.generate(List.of(rule), SieveStringEncoding.ESCAPED).text();
    assertEquals("# exo-rule 7\r\n"
        + "if anyof(address :contains \"from\" \"a\", address :contains \"to\" \"b\") {\r\n"
        + "  addflag \"\\\\Seen\";\r\n"
        + "  addflag \"\\\\Flagged\";\r\n"
        + "  addflag \"exo-filter-42\";\r\n"
        + "  fileinto \"Deleted Items\";\r\n"
        + "  stop;\r\n"
        + "}\r\n", text);
  }

  /**
   * Only what is emitted is required: filing needs {@code fileinto}, a flag
   * {@code imap4flags}; a disabled rule needs nothing; the system flags need
   * {@code encoded-character} on a server that un-escapes twice.
   */
  @Test
  public void testExtensions() {
    ServerRule move = rule("1", List.of(new Condition("FROM", "CONTAINS", null, "a")),
                           List.of(new Action("MOVE_TO_FOLDER", "ARCHIVE", "Archive", null)), false);
    ServerRule read = rule("2", List.of(new Condition("FROM", "CONTAINS", null, "a")), List.of(new Action("MARK_READ", null, null, null)), false);
    assertEquals(List.of("fileinto"), List.copyOf(generate(SieveStringEncoding.ESCAPED, move).extensions()));
    assertEquals(List.of("imap4flags"), List.copyOf(generate(SieveStringEncoding.ESCAPED, read).extensions()));
    assertEquals(List.of("fileinto", "imap4flags"), List.copyOf(generate(SieveStringEncoding.ESCAPED, move, read).extensions()));
    ServerRule off = new ServerRule("3", "Off", false, true, move.conditions(), move.actions(), true);
    SieveRulesSection.Generated none = generate(SieveStringEncoding.ESCAPED, off);
    assertEquals("", none.text());
    assertTrue(none.extensions().isEmpty());
    SieveRulesSection.Generated encoded = generate(SieveStringEncoding.ENCODED_CHARACTER, read);
    assertTrue(encoded.text().contains("addflag \"${unicode:5C}Seen\";"), encoded.text());
    assertEquals(List.of("imap4flags", "encoded-character"), List.copyOf(encoded.extensions()));
    assertFalse(encoded.text().contains("\\\\"));
  }

  /**
   * Where the server advertises {@code mailbox}, each {@code fileinto} is guarded by
   * {@code mailboxexists} and {@code mailbox} is required; a stale folder then leaves the
   * mail in the Inbox instead of failing the run, and the rules without filing need
   * neither.
   */
  @Test
  public void testTheMailboxGuard() {
    ServerRule move = rule("1", List.of(new Condition("FROM", "CONTAINS", null, "a")),
                           List.of(new Action("STAR", null, null, null), new Action("MOVE_TO_FOLDER", "CUSTOM:1", "Gone", null)), true);
    SieveRulesSection.Generated guarded = SieveRulesSection.generate(List.of(move), SieveStringEncoding.ESCAPED, true);
    assertEquals("# exo-rule 1\r\n"
        + "if address :contains \"from\" \"a\" {\r\n"
        + "  addflag \"\\\\Flagged\";\r\n"
        + "  if mailboxexists \"Gone\" {\r\n"
        + "    fileinto \"Gone\";\r\n"
        + "  }\r\n"
        + "  stop;\r\n"
        + "}\r\n", guarded.text());
    assertEquals(List.of("fileinto", "mailbox", "imap4flags"), List.copyOf(guarded.extensions()));
    ServerRule read = rule("2", List.of(new Condition("FROM", "CONTAINS", null, "a")), List.of(new Action("MARK_READ", null, null, null)), false);
    assertEquals(List.of("imap4flags"), List.copyOf(SieveRulesSection.generate(List.of(read), SieveStringEncoding.ESCAPED, true).extensions()));
    assertFalse(SieveRulesSection.generate(List.of(move), SieveStringEncoding.ESCAPED).text().contains("mailboxexists"));
    ManageSieveCapabilities withMailbox = ManageSieveCapabilities.fromLines(List.of(List.of("SIEVE", "fileinto imap4flags mailbox")));
    ManageSieveCapabilities without = ManageSieveCapabilities.fromLines(List.of(List.of("SIEVE", "fileinto imap4flags")));
    ExoSieveScript script = ExoSieveScript.empty().withRules(List.of(move));
    assertTrue(script.toScript(withMailbox).contains("require [\"fileinto\", \"mailbox\", \"imap4flags\"];"));
    assertFalse(script.toScript(without).contains("mailbox"));
  }

  /**
   * The {@code $} of a value is protected by {@code ${unicode:24}} only while the script
   * does not require {@code variables}: every element of the vocabulary, with a reply,
   * under both encodings, generates a {@code require} without it.
   */
  @Test
  public void testTheScriptNeverRequiresVariables() {
    List<ServerRule> every = new ArrayList<>();
    int ref = 1;
    for (String field : ServerRule.FIELDS) {
      String operator = ServerRule.FLAG_FIELDS.contains(field) ? "IS_TRUE" : "MESSAGE_SIZE".equals(field) ? "GT" : "STARTS_WITH";
      String value = ServerRule.FLAG_FIELDS.contains(field) ? null : "MESSAGE_SIZE".equals(field) ? "10" : "${x} \\ $";
      every.add(rule(String.valueOf(ref++),
                     List.of(new Condition(field, operator, "HEADER".equals(field) ? "X-A" : null, value)),
                     List.of(new Action("MOVE_TO_FOLDER", "CUSTOM:1", "${box}", null), new Action("MARK_READ", null, null, null),
                             new Action("STAR", null, null, null), new Action("TAG", null, null, "exo-filter-1")),
                     true));
    }
    ExoSieveScript script = ExoSieveScript.empty()
                                          .withVacation(new ExoSieveScript.Vacation(true, null, null, null, "${s}", "${t}", "exo-vacation-1", 7))
                                          .withRules(every);
    for (SieveStringEncoding encoding : SieveStringEncoding.values()) {
      for (boolean guard : new boolean[] { true, false }) {
        String text = script.toScript(encoding, guard);
        String require = text.substring(text.indexOf("require ["), text.indexOf("];") + 2);
        assertFalse(require.contains(SieveStringEncoding.VARIABLES_EXTENSION), require);
      }
    }
    assertTrue(script.toScript(SieveStringEncoding.ENCODED_CHARACTER).contains("${unicode:24}{x}"));
  }

  /**
   * Hostile values in every string a user controls -- the condition values, a header
   * value, a folder -- stay inside their quoted strings: with the strings removed, the
   * code holds only allowlisted words, and exactly one block.
   */
  @Test
  public void testHostileInputCannotEscapeItsString() {
    String hostile = "\"; redirect \"x@evil.example\"; discard; } if true { vacation \"x\"; # \\";
    ServerRule rule = new ServerRule("9",
                                     hostile,
                                     true,
                                     true,
                                     List.of(new Condition("SUBJECT", "CONTAINS", null, hostile),
                                             new Condition("FROM", "STARTS_WITH", null, hostile),
                                             new Condition("HEADER", "ENDS_WITH", "X-Spam", hostile)),
                                     List.of(new Action("MOVE_TO_FOLDER", "CUSTOM:1", hostile, null), new Action("STAR", null, null, null)),
                                     true).validated();
    for (SieveStringEncoding encoding : SieveStringEncoding.values()) {
      String text = SieveRulesSection.generate(List.of(rule), encoding).text();
      String code = SieveTokenScan.withoutComments(text);
      Matcher strings = QUOTED.matcher(code);
      String bare = strings.replaceAll("\"\"");
      Set<String> words = new TreeSet<>();
      Matcher word = WORD.matcher(bare);
      while (word.find()) {
        words.add(word.group());
      }
      assertTrue(ALLOWED.containsAll(words), encoding + ": " + words);
      assertEquals(1, bare.split("\\{", -1).length - 1, encoding + ": " + bare);
      List<String> literals = new ArrayList<>();
      Matcher again = QUOTED.matcher(code);
      while (again.find()) {
        literals.add(again.group());
      }
      assertTrue(literals.stream().anyMatch(literal -> literal.contains("redirect")), encoding.toString());
    }
  }

  /**
   * The header JSON round-trips, and is validated on the way back.
   */
  @Test
  public void testJsonRoundTrip() {
    ServerRule rule = new ServerRule("5",
                                     "Big",
                                     true,
                                     false,
                                     List.of(new Condition("MESSAGE_SIZE", "GT", null, "5000"), new Condition("HEADER", "CONTAINS", "List-Id", "x")),
                                     List.of(new Action("MARK_JUNK", "JUNK", "Junk Mail", null)),
                                     false).validated();
    assertEquals(List.of(rule), SieveRulesSection.fromJson(SieveRulesSection.toJson(List.of(rule))));
  }

  /**
   * One condition's test.
   *
   * @param field the field
   * @param operator the operator
   * @param header the header name
   * @param value the value
   * @return the test, as the rule's {@code if} carries it
   */
  private static String test(String field, String operator, String header, String value) {
    ServerRule rule = rule("1", List.of(new Condition(field, operator, header, value)), List.of(new Action("STAR", null, null, null)), false);
    String text = SieveRulesSection.generate(List.of(rule), SieveStringEncoding.ESCAPED).text();
    String line = text.split("\r\n")[1];
    return line.substring("if ".length(), line.length() - " {".length());
  }

  /**
   * A validated rule matching all its conditions.
   *
   * @param ref the reference
   * @param conditions the conditions
   * @param actions the actions
   * @param stop whether it stops
   * @return the rule
   */
  private static ServerRule rule(String ref, List<Condition> conditions, List<Action> actions, boolean stop) {
    return new ServerRule(ref, "Rule " + ref, true, true, conditions, actions, stop).validated();
  }

  /**
   * Generates rules.
   *
   * @param encoding the encoding
   * @param rules the rules
   * @return what the generator emitted
   */
  private static SieveRulesSection.Generated generate(SieveStringEncoding encoding, ServerRule... rules) {
    return SieveRulesSection.generate(List.of(rules), encoding);
  }
}
