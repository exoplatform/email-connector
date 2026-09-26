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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One server-side rule, as a rules engine lists and saves it: conditions over the
 * incoming mail, joined by "all" or "any", and the actions the server applies at
 * delivery when they hold.
 * <p>
 * The vocabulary is a <b>closed allowlist</b>, the same for every engine: an engine
 * serialises nothing outside it. The actions are the ones a mail survives -- a move, a
 * move to Junk or Trash, a flag, the keyword eXo's own rules pick up at sync; never a
 * forward, a reply, a discard or a rejection. {@link #validated()} is what every write
 * goes through, and what an engine reading its own rules back goes through again: a
 * value that would not pass on the way in is not read on the way back either.
 *
 * @param ref the engine's reference of the rule, stable across saves; null for a rule
 *          not saved yet
 * @param name the rule's name, as the user gave it
 * @param enabled whether the server applies it
 * @param matchAll true when every condition must hold, false when any one is enough
 * @param conditions the conditions, at least one
 * @param actions the actions, at least one, each type at most once
 * @param stop whether a mail this rule matched is left alone by the rules after it
 */
public record ServerRule(String ref,
                         String name,
                         boolean enabled,
                         boolean matchAll,
                         List<Condition> conditions,
                         List<Action> actions,
                         boolean stop) {

  /** Rule condition on the sender's address. */
  public static final String       FROM             = ServerRuleCapabilities.FROM;

  /** Rule condition on the To addresses. */
  public static final String       TO               = ServerRuleCapabilities.TO;

  /** Rule condition on the Cc addresses. */
  public static final String       CC               = ServerRuleCapabilities.CC;

  /** Rule condition on the To or Cc addresses. */
  public static final String       ANY_RECIPIENT    = ServerRuleCapabilities.ANY_RECIPIENT;

  /** Rule condition on the subject. */
  public static final String       SUBJECT          = ServerRuleCapabilities.SUBJECT;

  /** Rule condition on a named header. */
  public static final String       HEADER           = ServerRuleCapabilities.HEADER;

  /** Rule condition on the size of the message, in kilobytes. */
  public static final String       MESSAGE_SIZE     = ServerRuleCapabilities.MESSAGE_SIZE;

  /** Rule condition: the message comes from a mailing list. */
  public static final String       IS_LIST          = ServerRuleCapabilities.IS_LIST;

  /** Rule condition: the message was sent by an automated sender. */
  public static final String       IS_AUTOMATED     = ServerRuleCapabilities.IS_AUTOMATED;

  /** The value contains the text. */
  public static final String       CONTAINS         = "CONTAINS";

  /** The value does not contain the text. */
  public static final String       NOT_CONTAINS     = "NOT_CONTAINS";

  /** The value is the text, ignoring case. */
  public static final String       EQUALS           = "EQUALS";

  /** The value starts with the text. */
  public static final String       STARTS_WITH      = "STARTS_WITH";

  /** The value ends with the text. */
  public static final String       ENDS_WITH        = "ENDS_WITH";

  /** The address is in the domain, or one of its subdomains. */
  public static final String       MATCHES_DOMAIN   = "MATCHES_DOMAIN";

  /** The message is larger than the value. */
  public static final String       GT               = "GT";

  /** The message is smaller than the value. */
  public static final String       LT               = "LT";

  /** The flag condition holds. */
  public static final String       IS_TRUE          = "IS_TRUE";

  /** The flag condition does not hold. */
  public static final String       IS_FALSE         = "IS_FALSE";

  /** Action: file the mail into a mirrored folder. */
  public static final String       MOVE_TO_FOLDER   = ServerRuleCapabilities.MOVE_TO_FOLDER;

  /** Action: file the mail into Junk. */
  public static final String       MARK_JUNK        = ServerRuleCapabilities.MARK_JUNK;

  /** Action: file the mail into Trash, from where it can be restored. */
  public static final String       DELETE           = ServerRuleCapabilities.DELETE;

  /** Action: mark the mail as read. */
  public static final String       MARK_READ        = ServerRuleCapabilities.MARK_READ;

  /** Action: star the mail. */
  public static final String       STAR             = ServerRuleCapabilities.STAR;

  /** Action: set the keyword eXo's own rules pick up at sync. */
  public static final String       TAG              = ServerRuleCapabilities.TAG;

  /** The prefix every keyword eXo sets begins with. */
  public static final String       TAG_PREFIX       = "exo-filter-";

  /** The longest name accepted. */
  public static final int          MAX_NAME_LENGTH  = 100;

  /** The longest text value accepted. */
  public static final int          MAX_VALUE_LENGTH = 500;

  /** The longest folder path accepted. */
  public static final int          MAX_FOLDER_LENGTH = 500;

  /** The most conditions a rule may have. */
  public static final int          MAX_CONDITIONS   = 10;

  /** The largest size condition accepted, in kilobytes (about 10 GB). */
  public static final long         MAX_SIZE_KB      = 10_000_000L;

  /** The code of an invalid rule, the exception's message. */
  public static final String       INVALID          = "emailConnector.rules.invalid";

  /** The code of an invalid name. */
  public static final String       INVALID_NAME     = "emailConnector.rules.name.invalid";

  /** The code of an invalid condition. */
  public static final String       INVALID_CONDITION = "emailConnector.rules.condition.invalid";

  /** The code of an invalid action. */
  public static final String       INVALID_ACTION   = "emailConnector.rules.action.invalid";

  /** The fields whose value is an address. */
  public static final Set<String>  ADDRESS_FIELDS   = Set.of(FROM, TO, CC, ANY_RECIPIENT);

  /** The fields whose value is text. */
  public static final Set<String>  TEXT_FIELDS      = Set.of(SUBJECT, HEADER);

  /** The fields that are either true or false of a mail. */
  public static final Set<String>  FLAG_FIELDS      = Set.of(IS_LIST, IS_AUTOMATED);

  /** Every condition field, in the form's order. */
  public static final List<String> FIELDS           = List.of(FROM,
                                                              TO,
                                                              CC,
                                                              ANY_RECIPIENT,
                                                              SUBJECT,
                                                              HEADER,
                                                              MESSAGE_SIZE,
                                                              IS_LIST,
                                                              IS_AUTOMATED);

  /** The operators on text. */
  public static final List<String> TEXT_OPERATORS   = List.of(CONTAINS, NOT_CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH);

  /** Every action type, in the form's order. */
  public static final List<String> ACTION_TYPES     = List.of(MOVE_TO_FOLDER, MARK_JUNK, DELETE, MARK_READ, STAR, TAG);

  /** The actions that file the mail somewhere, of which a rule has at most one. */
  public static final Set<String>  FILING_ACTIONS   = Set.of(MOVE_TO_FOLDER, MARK_JUNK, DELETE);

  private static final Pattern     REF              = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private static final Pattern     HEADER_NAME      = Pattern.compile("[A-Za-z0-9-]{1,76}");

  private static final Pattern     DOMAIN           = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?"
      + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?)*");

  private static final Pattern     KEYWORD          = Pattern.compile(Pattern.quote(TAG_PREFIX) + "[A-Za-z0-9._-]{1,48}");

  private static final Pattern     SIZE             = Pattern.compile("[1-9][0-9]{0,7}");

  /**
   * Keeps the lists unmodifiable, and never null.
   *
   * @param ref the reference
   * @param name the name
   * @param enabled whether it applies
   * @param matchAll all or any
   * @param conditions the conditions
   * @param actions the actions
   * @param stop whether the rules after it are skipped
   */
  public ServerRule {
    conditions = conditions == null ? List.of() : List.copyOf(conditions);
    actions = actions == null ? List.of() : List.copyOf(actions);
  }

  /**
   * One condition.
   *
   * @param field one of {@link #FIELDS}
   * @param operator an operator the field accepts
   * @param header the header's name, for {@link #HEADER} only
   * @param value the value compared with, absent for a flag field
   */
  public record Condition(String field, String operator, String header, String value) {
  }

  /**
   * One action.
   *
   * @param type one of {@link #ACTION_TYPES}
   * @param folderKey the eXo key of the folder a move targets ({@code CUSTOM:<id>},
   *          {@code ARCHIVE}); the form's value, kept for the round trip
   * @param folderPath the folder's name on the mail server, which the engine writes;
   *          resolved by eXo from its mirrored folders, never taken from the user
   * @param keyword the keyword a {@link #TAG} sets, {@value #TAG_PREFIX} followed by
   *          letters, digits, dots, underscores or dashes
   */
  public record Action(String type, String folderKey, String folderPath, String keyword) {
  }

  /**
   * The same rule under another reference.
   *
   * @param newRef the reference
   * @return the rule
   */
  public ServerRule withRef(String newRef) {
    return new ServerRule(newRef, name, enabled, matchAll, conditions, actions, stop);
  }

  /**
   * The same rule with other actions.
   *
   * @param newActions the actions
   * @return the rule
   */
  public ServerRule withActions(List<Action> newActions) {
    return new ServerRule(ref, name, enabled, matchAll, conditions, newActions, stop);
  }

  /**
   * Whether this rule, when it runs, files the mail somewhere or stops the rules after
   * it: what decides whether another client's script must run before eXo's.
   *
   * @return true when it has a filing action or {@code stop}
   */
  public boolean filesOrStops() {
    return stop || actions.stream().anyMatch(action -> FILING_ACTIONS.contains(action.type()));
  }

  /**
   * Whether every action of this rule is the eXo keyword: the server half of a rule
   * whose other half runs in eXo.
   *
   * @return true for a hop
   */
  public boolean isHop() {
    return !actions.isEmpty() && actions.stream().allMatch(action -> TAG.equals(action.type()));
  }

  /**
   * Whether a reference is one eXo can write into a script and a URL as is.
   *
   * @param value the reference
   * @return true for letters, digits, dots, underscores and dashes, at most 64
   */
  public static boolean isValidRef(String value) {
    return value != null && REF.matcher(value).matches();
  }

  /**
   * Validates the rule against the closed vocabulary and normalises it: codes upper-case,
   * texts trimmed, a header name lower-case, fields a condition does not use dropped.
   * The folder of a filing action must already be resolved.
   *
   * @return the normalised rule
   * @throws IllegalArgumentException with a message code when anything is outside the
   *           vocabulary or its bounds
   */
  public ServerRule validated() {
    if (ref != null && !isValidRef(ref)) {
      throw new IllegalArgumentException(INVALID);
    }
    String cleanName = name == null ? "" : name.trim();
    if (cleanName.isEmpty() || cleanName.length() > MAX_NAME_LENGTH || hasControl(cleanName)) {
      throw new IllegalArgumentException(INVALID_NAME);
    }
    if (conditions.isEmpty() || conditions.size() > MAX_CONDITIONS) {
      throw new IllegalArgumentException(INVALID_CONDITION);
    }
    List<Condition> cleanConditions = new ArrayList<>();
    for (Condition condition : conditions) {
      cleanConditions.add(validated(condition));
    }
    if (actions.isEmpty()) {
      throw new IllegalArgumentException(INVALID_ACTION);
    }
    Set<String> types = new HashSet<>();
    int filing = 0;
    List<Action> cleanActions = new ArrayList<>();
    for (Action action : actions) {
      Action clean = validated(action);
      if (!types.add(clean.type())) {
        throw new IllegalArgumentException(INVALID_ACTION);
      }
      if (FILING_ACTIONS.contains(clean.type())) {
        filing++;
      }
      cleanActions.add(clean);
    }
    if (filing > 1) {
      // Two filing actions would leave two copies of the mail.
      throw new IllegalArgumentException(INVALID_ACTION);
    }
    return new ServerRule(ref, cleanName, enabled, matchAll, cleanConditions, cleanActions, stop);
  }

  /**
   * Validates one condition.
   *
   * @param condition the condition
   * @return the normalised condition
   * @throws IllegalArgumentException {@value #INVALID_CONDITION}
   */
  private static Condition validated(Condition condition) {
    if (condition == null || condition.field() == null || condition.operator() == null) {
      throw new IllegalArgumentException(INVALID_CONDITION);
    }
    String field = condition.field().trim().toUpperCase(Locale.ROOT);
    String operator = condition.operator().trim().toUpperCase(Locale.ROOT);
    if (FLAG_FIELDS.contains(field)) {
      if (!IS_TRUE.equals(operator) && !IS_FALSE.equals(operator)) {
        throw new IllegalArgumentException(INVALID_CONDITION);
      }
      return new Condition(field, operator, null, null);
    }
    String value = condition.value() == null ? "" : condition.value().trim();
    if (MESSAGE_SIZE.equals(field)) {
      if (!GT.equals(operator) && !LT.equals(operator) || !SIZE.matcher(value).matches()
          || Long.parseLong(value) > MAX_SIZE_KB) {
        throw new IllegalArgumentException(INVALID_CONDITION);
      }
      return new Condition(field, operator, null, value);
    }
    if (value.isEmpty() || value.length() > MAX_VALUE_LENGTH || hasControl(value)) {
      throw new IllegalArgumentException(INVALID_CONDITION);
    }
    if (ADDRESS_FIELDS.contains(field)) {
      if (MATCHES_DOMAIN.equals(operator)) {
        String domain = value.startsWith("@") ? value.substring(1) : value;
        if (!DOMAIN.matcher(domain).matches()) {
          throw new IllegalArgumentException(INVALID_CONDITION);
        }
        return new Condition(field, operator, null, domain.toLowerCase(Locale.ROOT));
      }
      if (!TEXT_OPERATORS.contains(operator)) {
        throw new IllegalArgumentException(INVALID_CONDITION);
      }
      return new Condition(field, operator, null, value);
    }
    if (!TEXT_FIELDS.contains(field) || !TEXT_OPERATORS.contains(operator)) {
      throw new IllegalArgumentException(INVALID_CONDITION);
    }
    if (HEADER.equals(field)) {
      String header = condition.header() == null ? "" : condition.header().trim();
      if (!HEADER_NAME.matcher(header).matches()) {
        throw new IllegalArgumentException(INVALID_CONDITION);
      }
      return new Condition(field, operator, header.toLowerCase(Locale.ROOT), value);
    }
    return new Condition(field, operator, null, value);
  }

  /**
   * Validates one action.
   *
   * @param action the action
   * @return the normalised action
   * @throws IllegalArgumentException {@value #INVALID_ACTION}
   */
  private static Action validated(Action action) {
    if (action == null || action.type() == null) {
      throw new IllegalArgumentException(INVALID_ACTION);
    }
    String type = action.type().trim().toUpperCase(Locale.ROOT);
    if (!ACTION_TYPES.contains(type)) {
      throw new IllegalArgumentException(INVALID_ACTION);
    }
    if (FILING_ACTIONS.contains(type)) {
      String path = action.folderPath();
      if (path == null || path.isBlank() || path.length() > MAX_FOLDER_LENGTH || hasControl(path)) {
        throw new IllegalArgumentException(INVALID_ACTION);
      }
      String key = action.folderKey();
      if (key != null && (key.length() > MAX_FOLDER_LENGTH || hasControl(key))) {
        throw new IllegalArgumentException(INVALID_ACTION);
      }
      return new Action(type, key, path, null);
    }
    if (TAG.equals(type)) {
      if (action.keyword() == null || !KEYWORD.matcher(action.keyword()).matches()) {
        throw new IllegalArgumentException(INVALID_ACTION);
      }
      return new Action(type, null, null, action.keyword());
    }
    return new Action(type, null, null, null);
  }

  /**
   * Whether a text holds a control character (a line break, a NUL, a tab, DEL, the C1
   * range) or a Unicode line or paragraph separator.
   *
   * @param text the text
   * @return true when one is found
   */
  private static boolean hasControl(String text) {
    return text.chars().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.LINE_SEPARATOR
        || Character.getType(c) == Character.PARAGRAPH_SEPARATOR);
  }
}
