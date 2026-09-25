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

import static org.exoplatform.emailConnector.service.rules.sieve.ExoSieveScript.EOL;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRule.Action;
import org.exoplatform.emailConnector.model.ServerRule.Condition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The rules section of eXo's script: the rules as header JSON, and the Sieve generated
 * from them.
 * <p>
 * The generator is a closed allowlist. Tests: {@code address}, {@code header},
 * {@code exists}, {@code size}, under {@code allof}/{@code anyof}/{@code not}, with the
 * {@code :contains}, {@code :is} and {@code :matches} match types and the
 * {@code :domain} address part. Actions: {@code addflag} (imap4flags, never
 * {@code setflag}, which would clear the flags another rule set), {@code fileinto} and
 * {@code stop}. Nothing else is ever written: no {@code redirect}, {@code vacation},
 * {@code discard}, {@code reject}, {@code keep :flags}. Every string, user-typed or not,
 * becomes a quoted string through the server's {@link SieveStringEncoding}, so a value
 * cannot close its string, and a {@code :matches} value has its wildcards escaped first,
 * so it matches as typed.
 * <p>
 * In a rule's block the flags come first, then the filing, then {@code stop}: a flag set
 * after a {@code fileinto} would not reach the copy it filed (RFC 5232 §3.1), and nothing
 * after {@code stop} runs. A disabled rule stays in the header and emits no Sieve.
 * <p>
 * Where the server advertises {@code mailbox} (RFC 5490), each {@code fileinto} is guarded
 * by {@code mailboxexists}: a folder renamed or deleted in another client since the rule
 * was saved fails the whole run at delivery otherwise -- the mail kept in the Inbox, and
 * the automatic reply and the hop keyword lost with it for every mail the rule matches
 * (observed on Pigeonhole). Guarded, the mail stays in the Inbox and the rest of the run
 * goes on. {@code fileinto :create} is not used: it would bring back a folder the user
 * deleted.
 */
final class SieveRulesSection {

  /** The comment in front of each rule's block; the reference is a validated token. */
  static final String         RULE_COMMENT       = "# exo-rule ";

  /** The extension {@code fileinto} needs. */
  static final String         FILEINTO_EXTENSION = "fileinto";

  /** The extension {@code mailboxexists} needs (RFC 5490). */
  static final String         MAILBOX_EXTENSION  = "mailbox";

  /** The extension {@code addflag} needs. */
  static final String         FLAGS_EXTENSION    = "imap4flags";

  /** The system flag of a read mail. */
  static final String         SEEN_FLAG          = "\\Seen";

  /** The system flag of a starred mail. */
  static final String         FLAGGED_FLAG       = "\\Flagged";

  /** The header key of the match type: all conditions, or any. */
  static final String         MATCH_ALL          = "ALL";

  /** The header value of "any condition". */
  static final String         MATCH_ANY          = "ANY";

  /**
   * Utility class.
   */
  private SieveRulesSection() {
  }

  /**
   * The Sieve of the rules, and the extensions it needs.
   *
   * @param text the Sieve, CRLF line endings; empty for no enabled rule
   * @param extensions the extensions to require, in a stable order
   */
  record Generated(String text, Set<String> extensions) {
  }

  /**
   * Generates the Sieve of the enabled rules, in order, each {@code fileinto} unguarded.
   *
   * @param rules the rules, validated
   * @param encoding how strings become quoted strings on this server
   * @return the text and what it requires
   * @throws IllegalStateException when a rule holds something outside the allowlist,
   *           which validation should have refused
   */
  static Generated generate(List<ServerRule> rules, SieveStringEncoding encoding) {
    return generate(rules, encoding, false);
  }

  /**
   * Generates the Sieve of the enabled rules, in order.
   *
   * @param rules the rules, validated
   * @param encoding how strings become quoted strings on this server
   * @param mailboxGuard whether to guard each {@code fileinto} with {@code mailboxexists},
   *          which the server must advertise ({@code mailbox})
   * @return the text and what it requires
   * @throws IllegalStateException when a rule holds something outside the allowlist,
   *           which validation should have refused
   */
  static Generated generate(List<ServerRule> rules, SieveStringEncoding encoding, boolean mailboxGuard) {
    Quoter quoter = new Quoter(encoding);
    boolean files = false;
    boolean flags = false;
    StringBuilder text = new StringBuilder();
    for (ServerRule rule : rules) {
      if (!rule.enabled()) {
        continue;
      }
      text.append(RULE_COMMENT).append(rule.ref()).append(EOL);
      text.append("if ").append(test(rule, quoter)).append(" {").append(EOL);
      for (String type : List.of(ServerRule.MARK_READ, ServerRule.STAR, ServerRule.TAG)) {
        Action action = action(rule, type);
        if (action != null) {
          flags = true;
          text.append("  addflag ").append(quoter.quote(flag(action))).append(';').append(EOL);
        }
      }
      for (Action action : rule.actions()) {
        if (ServerRule.FILING_ACTIONS.contains(action.type())) {
          files = true;
          String folder = quoter.quote(action.folderPath());
          if (mailboxGuard) {
            text.append("  if mailboxexists ").append(folder).append(" {").append(EOL);
            text.append("    fileinto ").append(folder).append(';').append(EOL);
            text.append("  }").append(EOL);
          } else {
            text.append("  fileinto ").append(folder).append(';').append(EOL);
          }
        } else if (!ServerRule.ACTION_TYPES.contains(action.type())) {
          throw new IllegalStateException("No Sieve for the action " + action.type());
        }
      }
      if (rule.stop()) {
        text.append("  stop;").append(EOL);
      }
      text.append('}').append(EOL);
    }
    Set<String> extensions = new LinkedHashSet<>();
    if (files) {
      extensions.add(FILEINTO_EXTENSION);
      if (mailboxGuard) {
        extensions.add(MAILBOX_EXTENSION);
      }
    }
    if (flags) {
      extensions.add(FLAGS_EXTENSION);
    }
    if (quoter.needsExtension) {
      extensions.add(SieveStringEncoding.EXTENSION);
    }
    return new Generated(text.toString(), extensions);
  }

  /**
   * The action of a type in a rule.
   *
   * @param rule the rule
   * @param type the type
   * @return the action, or null
   */
  private static Action action(ServerRule rule, String type) {
    return rule.actions().stream().filter(action -> type.equals(action.type())).findFirst().orElse(null);
  }

  /**
   * The flag an action sets.
   *
   * @param action a flag action
   * @return the flag or keyword
   */
  private static String flag(Action action) {
    return switch (action.type()) {
    case ServerRule.MARK_READ -> SEEN_FLAG;
    case ServerRule.STAR -> FLAGGED_FLAG;
    case ServerRule.TAG -> action.keyword();
    default -> throw new IllegalStateException("Not a flag action: " + action.type());
    };
  }

  /**
   * The rule's test: its one condition, or all or any of them.
   *
   * @param rule the rule
   * @param quoter the string encoder
   * @return the test
   */
  private static String test(ServerRule rule, Quoter quoter) {
    List<String> tests = new ArrayList<>();
    for (Condition condition : rule.conditions()) {
      tests.add(test(condition, quoter));
    }
    if (tests.size() == 1) {
      return tests.get(0);
    }
    return (rule.matchAll() ? "allof(" : "anyof(") + String.join(", ", tests) + ")";
  }

  /**
   * One condition's test.
   *
   * @param condition the condition, validated
   * @param quoter the string encoder
   * @return the test
   */
  private static String test(Condition condition, Quoter quoter) {
    String field = condition.field();
    String operator = condition.operator();
    switch (field) {
    case ServerRule.FROM, ServerRule.TO, ServerRule.CC, ServerRule.ANY_RECIPIENT:
      return addressTest(field, operator, condition.value(), quoter);
    case ServerRule.SUBJECT:
      return textTest("header", quoter.quote("subject"), operator, condition.value(), quoter);
    case ServerRule.HEADER:
      return textTest("header", quoter.quote(condition.header()), operator, condition.value(), quoter);
    case ServerRule.MESSAGE_SIZE:
      if (ServerRule.GT.equals(operator)) {
        return "size :over " + Long.parseLong(condition.value()) + "K";
      }
      if (ServerRule.LT.equals(operator)) {
        return "size :under " + Long.parseLong(condition.value()) + "K";
      }
      break;
    case ServerRule.IS_LIST:
      return negate(operator, "anyof(exists " + quoter.quote("list-id") + ", exists " + quoter.quote("list-post")
          + ", exists " + quoter.quote("list-unsubscribe") + ")");
    case ServerRule.IS_AUTOMATED:
      return negate(operator, "allof(exists " + quoter.quote("auto-submitted") + ", not header :is "
          + quoter.quote("auto-submitted") + " " + quoter.quote("no") + ")");
    default:
      break;
    }
    throw new IllegalStateException("No Sieve for the condition " + field + " " + operator);
  }

  /**
   * An address condition's test: a text comparison of the addresses, or their domain
   * being the value or one of its subdomains.
   *
   * @param field the address field
   * @param operator the operator
   * @param value the value, a validated domain for {@code MATCHES_DOMAIN}
   * @param quoter the string encoder
   * @return the test
   */
  private static String addressTest(String field, String operator, String value, Quoter quoter) {
    String headers = addressHeaders(field);
    if (ServerRule.MATCHES_DOMAIN.equals(operator)) {
      return "anyof(address :domain :is " + headers + " " + quoter.quote(value) + ", address :domain :matches " + headers
          + " " + quoter.quote("*." + pattern(value)) + ")";
    }
    return textTest("address", headers, operator, value, quoter);
  }

  /**
   * The header list an address condition reads.
   *
   * @param field the field
   * @return the Sieve string or string list
   */
  private static String addressHeaders(String field) {
    return switch (field) {
    case ServerRule.FROM -> "\"from\"";
    case ServerRule.TO -> "\"to\"";
    case ServerRule.CC -> "\"cc\"";
    default -> "[\"to\", \"cc\"]";
    };
  }

  /**
   * A text comparison with one of the text operators.
   *
   * @param command {@code address} or {@code header}
   * @param headers the header names, already Sieve
   * @param operator the operator
   * @param value the value as typed
   * @param quoter the string encoder
   * @return the test
   */
  private static String textTest(String command, String headers, String operator, String value, Quoter quoter) {
    return switch (operator) {
    case ServerRule.CONTAINS -> command + " :contains " + headers + " " + quoter.quote(value);
    case ServerRule.NOT_CONTAINS -> "not " + command + " :contains " + headers + " " + quoter.quote(value);
    case ServerRule.EQUALS -> command + " :is " + headers + " " + quoter.quote(value);
    case ServerRule.STARTS_WITH -> command + " :matches " + headers + " " + quoter.quote(pattern(value) + "*");
    case ServerRule.ENDS_WITH -> command + " :matches " + headers + " " + quoter.quote("*" + pattern(value));
    default -> throw new IllegalStateException("No Sieve for the operator " + operator);
    };
  }

  /**
   * A test, or its negation.
   *
   * @param operator {@code IS_TRUE} or {@code IS_FALSE}
   * @param test the test of "true"
   * @return the test
   */
  private static String negate(String operator, String test) {
    if (ServerRule.IS_TRUE.equals(operator)) {
      return test;
    }
    if (ServerRule.IS_FALSE.equals(operator)) {
      return "not " + test;
    }
    throw new IllegalStateException("No Sieve for the operator " + operator);
  }

  /**
   * A value as a {@code :matches} pattern that matches it literally (RFC 5228 §2.7.1):
   * the backslash, {@code *} and {@code ?} escaped with a backslash, before the string
   * itself is encoded.
   *
   * @param value the value
   * @return the pattern
   */
  static String pattern(String value) {
    return value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
  }

  /**
   * The rules as header JSON.
   *
   * @param rules the rules
   * @return the array
   */
  static ArrayNode toJson(List<ServerRule> rules) {
    ArrayNode array = JsonNodeFactory.instance.arrayNode();
    for (ServerRule rule : rules) {
      ObjectNode node = array.addObject();
      node.put("id", rule.ref());
      node.put("name", rule.name());
      node.put("enabled", rule.enabled());
      node.put("match", rule.matchAll() ? MATCH_ALL : MATCH_ANY);
      ArrayNode conditions = node.putArray("conditions");
      for (Condition condition : rule.conditions()) {
        ObjectNode item = conditions.addObject();
        item.put("field", condition.field());
        item.put("operator", condition.operator());
        putIfPresent(item, "header", condition.header());
        putIfPresent(item, "value", condition.value());
      }
      ArrayNode actions = node.putArray("actions");
      for (Action action : rule.actions()) {
        ObjectNode item = actions.addObject();
        item.put("type", action.type());
        putIfPresent(item, "folderKey", action.folderKey());
        putIfPresent(item, "folder", action.folderPath());
        putIfPresent(item, "keyword", action.keyword());
      }
      node.put("stop", rule.stop());
    }
    return array;
  }

  /**
   * Reads the rules back from header JSON, validating each as a write would.
   *
   * @param array the array
   * @return the rules, in order
   * @throws IllegalArgumentException when a rule is malformed, invalid, or two share a
   *           reference
   */
  static List<ServerRule> fromJson(ArrayNode array) {
    List<ServerRule> rules = new ArrayList<>();
    Set<String> refs = new HashSet<>();
    for (JsonNode node : array) {
      if (!node.isObject() || !node.path("enabled").isBoolean() || !node.path("stop").isBoolean()
          || !node.path("conditions").isArray() || !node.path("actions").isArray()) {
        throw new IllegalArgumentException(ServerRule.INVALID);
      }
      String ref = string(node, "id");
      String match = string(node, "match");
      if (!ServerRule.isValidRef(ref) || !refs.add(ref) || !MATCH_ALL.equals(match) && !MATCH_ANY.equals(match)) {
        throw new IllegalArgumentException(ServerRule.INVALID);
      }
      List<Condition> conditions = new ArrayList<>();
      for (JsonNode item : node.path("conditions")) {
        conditions.add(new Condition(string(item, "field"), string(item, "operator"), string(item, "header"), string(item, "value")));
      }
      List<Action> actions = new ArrayList<>();
      for (JsonNode item : node.path("actions")) {
        actions.add(new Action(string(item, "type"), string(item, "folderKey"), string(item, "folder"), string(item, "keyword")));
      }
      rules.add(new ServerRule(ref,
                               string(node, "name"),
                               node.path("enabled").booleanValue(),
                               MATCH_ALL.equals(match),
                               conditions,
                               actions,
                               node.path("stop").booleanValue()).validated());
    }
    return rules;
  }

  /**
   * Writes a field when it has a value.
   *
   * @param node the object
   * @param field the field
   * @param value the value, possibly null
   */
  private static void putIfPresent(ObjectNode node, String field, String value) {
    if (value != null) {
      node.put(field, value);
    }
  }

  /**
   * A string field, null when absent or not a string.
   *
   * @param node the object
   * @param field the field
   * @return the value or null
   */
  private static String string(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || !value.isString() ? null : value.asString();
  }

  /**
   * Quotes strings with one encoding and remembers whether any needed its extension.
   */
  private static final class Quoter {

    private final SieveStringEncoding encoding;

    private boolean                   needsExtension;

    /**
     * A quoter.
     *
     * @param encoding the server's encoding
     */
    private Quoter(SieveStringEncoding encoding) {
      this.encoding = encoding;
    }

    /**
     * Encodes a value as a quoted string.
     *
     * @param value the value
     * @return the quoted string
     */
    private String quote(String value) {
      needsExtension |= encoding.needsExtension(value);
      return encoding.quote(value);
    }
  }
}
