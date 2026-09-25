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
package org.exoplatform.emailConnector.service.filters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.exoplatform.emailConnector.model.ServerRule;

/**
 * Evaluates a rule's conditions on one mail, the way the server evaluates the same
 * vocabulary: texts compared ignoring case, an address condition true when one of the
 * addresses satisfies it -- and, for {@code NOT_CONTAINS}, when none contains the value,
 * as Sieve's {@code not address :contains} reads. No regular expression anywhere: every
 * value is a user's, and evaluated on the sync's thread.
 * <p>
 * Three-valued: a condition on something eXo does not know of the mail (a header, the
 * size of a mail cached before) is {@link Result#UNKNOWN}, and so is a rule whose answer
 * depends on it. The sync never acts on an unknown answer.
 */
public final class FilterConditionEvaluator {

  /** A field only eXo evaluates: the body. */
  public static final String BODY            = "BODY";

  /** A field only eXo evaluates: the subject or the body. */
  public static final String SUBJECT_OR_BODY = "SUBJECT_OR_BODY";

  /** A field only eXo evaluates: the mail has an attachment. */
  public static final String HAS_ATTACHMENT  = "HAS_ATTACHMENT";

  /** The answer of a condition, or of a rule. */
  public enum Result {
    /** It holds. */
    TRUE,
    /** It does not hold. */
    FALSE,
    /** It depends on something eXo does not know of the mail. */
    UNKNOWN;

    /**
     * The answer of a boolean.
     *
     * @param value the boolean
     * @return {@link #TRUE} or {@link #FALSE}
     */
    static Result of(boolean value) {
      return value ? TRUE : FALSE;
    }
  }

  /**
   * A utility class.
   */
  private FilterConditionEvaluator() {
  }

  /**
   * Evaluates conditions joined by "all" or "any".
   *
   * @param conditions the conditions, validated
   * @param matchAll true when every one must hold
   * @param mail the mail
   * @return the answer; {@link Result#FALSE} for no condition at all, which no valid
   *         rule has
   */
  public static Result evaluate(List<ServerRule.Condition> conditions, boolean matchAll, FilterMail mail) {
    if (conditions == null || conditions.isEmpty()) {
      return Result.FALSE;
    }
    boolean unknown = false;
    for (ServerRule.Condition condition : conditions) {
      Result result = evaluate(condition, mail);
      if (matchAll && result == Result.FALSE) {
        return Result.FALSE;
      }
      if (!matchAll && result == Result.TRUE) {
        return Result.TRUE;
      }
      unknown = unknown || result == Result.UNKNOWN;
    }
    if (unknown) {
      return Result.UNKNOWN;
    }
    return matchAll ? Result.TRUE : Result.FALSE;
  }

  /**
   * Evaluates one condition.
   *
   * @param condition the condition, validated
   * @param mail the mail
   * @return the answer
   */
  public static Result evaluate(ServerRule.Condition condition, FilterMail mail) {
    String field = condition.field();
    String operator = condition.operator();
    String value = condition.value();
    switch (field) {
    case ServerRule.FROM:
      return addresses(mail.from() == null ? List.of() : List.of(mail.from()), operator, value);
    case ServerRule.TO:
      return addresses(mail.to(), operator, value);
    case ServerRule.CC:
      return addresses(mail.cc(), operator, value);
    case ServerRule.ANY_RECIPIENT:
      List<String> recipients = new ArrayList<>(mail.to());
      recipients.addAll(mail.cc());
      return addresses(recipients, operator, value);
    case ServerRule.SUBJECT:
      return Result.of(text(mail.subject(), operator, value));
    case BODY:
      return Result.of(text(mail.bodyText(), operator, value));
    case SUBJECT_OR_BODY:
      if (ServerRule.NOT_CONTAINS.equals(operator)) {
        return Result.of(text(mail.subject(), operator, value) && text(mail.bodyText(), operator, value));
      }
      return Result.of(text(mail.subject(), operator, value) || text(mail.bodyText(), operator, value));
    case ServerRule.HEADER:
      List<String> values = mail.header(condition.header());
      if (values == null) {
        return Result.UNKNOWN;
      }
      return anyOrNone(values, operator, value);
    case ServerRule.MESSAGE_SIZE:
      Long size = mail.sizeKb();
      if (size == null) {
        return Result.UNKNOWN;
      }
      long bound = Long.parseLong(value);
      // The size is in kilobytes rounded up, so "under N K" -- fewer than N*1024 bytes, as
      // Sieve reads it -- is at most N here, short of the one byte count N*1024 itself.
      return Result.of(ServerRule.GT.equals(operator) ? size > bound : size <= bound);
    case ServerRule.IS_LIST:
      return flag(mail.isList(), operator);
    case ServerRule.IS_AUTOMATED:
      return flag(mail.isAutomated(), operator);
    case HAS_ATTACHMENT:
      return flag(mail.hasAttachment(), operator);
    default:
      return Result.FALSE;
    }
  }

  /**
   * A flag condition.
   *
   * @param flag what the mail is
   * @param operator {@code IS_TRUE} or {@code IS_FALSE}
   * @return the answer
   */
  private static Result flag(boolean flag, String operator) {
    return Result.of(ServerRule.IS_TRUE.equals(operator) == flag);
  }

  /**
   * An address condition over several addresses.
   *
   * @param addresses the addresses
   * @param operator the operator
   * @param value the value
   * @return true when one address satisfies it; for {@code NOT_CONTAINS}, when none
   *         contains the value
   */
  private static Result addresses(List<String> addresses, String operator, String value) {
    if (ServerRule.MATCHES_DOMAIN.equals(operator)) {
      return Result.of(addresses.stream().anyMatch(address -> inDomain(address, value)));
    }
    return anyOrNone(addresses, operator, value);
  }

  /**
   * A text condition over several values.
   *
   * @param values the values
   * @param operator the operator
   * @param value the value compared with
   * @return true when one value satisfies it; for {@code NOT_CONTAINS}, when none
   *         contains it
   */
  private static Result anyOrNone(List<String> values, String operator, String value) {
    if (ServerRule.NOT_CONTAINS.equals(operator)) {
      return Result.of(values.stream().noneMatch(candidate -> text(candidate, ServerRule.CONTAINS, value)));
    }
    return Result.of(values.stream().anyMatch(candidate -> text(candidate, operator, value)));
  }

  /**
   * A text comparison, ignoring case.
   *
   * @param candidate the mail's text, null when absent
   * @param operator the operator
   * @param value the value compared with
   * @return the answer; an absent text contains nothing
   */
  private static boolean text(String candidate, String operator, String value) {
    String text = candidate == null ? "" : candidate.toLowerCase(Locale.ROOT);
    String expected = value == null ? "" : value.toLowerCase(Locale.ROOT);
    return switch (operator) {
    case ServerRule.CONTAINS -> text.contains(expected);
    case ServerRule.NOT_CONTAINS -> !text.contains(expected);
    case ServerRule.EQUALS -> text.trim().equals(expected);
    case ServerRule.STARTS_WITH -> text.startsWith(expected);
    case ServerRule.ENDS_WITH -> text.endsWith(expected);
    default -> false;
    };
  }

  /**
   * Whether an address is in a domain or one of its subdomains.
   *
   * @param address the address
   * @param domain the domain, lower-case
   * @return true for {@code x@domain} and {@code x@sub.domain}
   */
  private static boolean inDomain(String address, String domain) {
    if (address == null) {
      return false;
    }
    int at = address.lastIndexOf('@');
    String host = at < 0 ? "" : address.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    return host.equals(domain) || host.endsWith("." + domain);
  }
}
