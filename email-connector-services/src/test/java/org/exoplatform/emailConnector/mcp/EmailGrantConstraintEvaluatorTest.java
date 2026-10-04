/*
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
package org.exoplatform.emailConnector.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.meeds.mcp.server.model.McpToolGrantConstraint;

/**
 * Pins the "only to recipients in one domain" limit of standing approvals on
 * the mail tools: bare addresses only, exact IDNA-normalised domains (a
 * subdomain is another domain), bcc included, no shared mailbox and no other
 * person's name under any grant, and every doubt read as "no match".
 */
class EmailGrantConstraintEvaluatorTest {

  private static final String                 SEND      = "send_email";

  private static final McpToolGrantConstraint EXAMPLE   = domain("example.com");

  private final EmailGrantConstraintEvaluator evaluator = new EmailGrantConstraintEvaluator();

  /**
   * The evaluator rules the four tools that send mail, and only them.
   */
  @Test
  void supportsTheMailTools() {
    assertTrue(evaluator.supports("send_email"));
    assertTrue(evaluator.supports("reply_email"));
    assertTrue(evaluator.supports("reply_all"));
    assertTrue(evaluator.supports("forward_email"));
    assertFalse(evaluator.supports("delete_email"));
  }

  /**
   * A shared mailbox or another person's name keeps the mail on its card,
   * whatever grant exists; the user's own name, spelt blank or "me", doesn't.
   */
  @Test
  void sharedMailboxOrOwnersNameForbidsAnyGrant() {
    assertTrue(evaluator.allowsStandingApproval(SEND, args("to", List.of("a@example.com"))));
    assertTrue(evaluator.allowsStandingApproval(SEND, args("identity", "me", "mailbox", " ")));
    assertFalse(evaluator.allowsStandingApproval(SEND, args("mailbox", "boss@example.com")));
    assertFalse(evaluator.allowsStandingApproval(SEND, args("identity", "owner")));
    assertFalse(evaluator.allowsStandingApproval(SEND, args("identity", "owner_on_behalf")));
    assertFalse(evaluator.allowsStandingApproval(SEND, args("identity", "ME")));
    assertFalse(evaluator.allowsStandingApproval(SEND, args("mailbox", List.of("boss@example.com"))));
    assertFalse(evaluator.allowsStandingApproval("delete_email", args()));
    assertFalse(evaluator.allowsStandingApproval(SEND, null));
    assertFalse(evaluator.matches(SEND, args("to", List.of("a@example.com"), "mailbox", "boss@example.com"), EXAMPLE));
  }

  /**
   * The card offers the domain when every recipient shares it, and nothing
   * otherwise.
   */
  @Test
  void proposesTheCommonDomainOnly() {
    assertEquals(EXAMPLE, evaluator.proposeConstraint(SEND, args("to", List.of("a@Example.com"), "cc", List.of("b@example.com"))));
    assertNull(evaluator.proposeConstraint(SEND, args("to", List.of("a@example.com"), "bcc", List.of("b@other.com"))));
    assertNull(evaluator.proposeConstraint(SEND, args("to", List.of("Bob <b@example.com>"))));
    assertNull(evaluator.proposeConstraint(SEND, args()));
    assertNull(evaluator.proposeConstraint(SEND, args("to", List.of("a@example.com"), "mailbox", "boss@example.com")));
  }

  /**
   * Recipients all in the domain match, case and Unicode spelling aside.
   */
  @Test
  void matchesRecipientsOfTheExactDomain() {
    assertTrue(evaluator.matches(SEND, args("to", List.of("a@example.com", " B@EXAMPLE.COM "), "cc", List.of("c@example.com")), EXAMPLE));
    assertTrue(evaluator.matches(SEND, args("to", List.of("a@bücher.de")), domain("xn--bcher-kva.de")));
    assertTrue(evaluator.matches(SEND, args("to", List.of("a@xn--bcher-kva.de")), domain("BÜCHER.de")));
  }

  /**
   * A subdomain, a parent domain or a look-alike suffix is another domain.
   */
  @Test
  void subdomainIsAnotherDomain() {
    assertFalse(evaluator.matches(SEND, args("to", List.of("a@mail.example.com")), EXAMPLE));
    assertFalse(evaluator.matches(SEND, args("to", List.of("a@com")), EXAMPLE));
    assertFalse(evaluator.matches(SEND, args("to", List.of("a@evilexample.com")), EXAMPLE));
    assertFalse(evaluator.matches(SEND, args("to", List.of("a@example.com.evil.org")), EXAMPLE));
  }

  /**
   * A blind copy outside the domain breaks the limit, whatever the tool.
   */
  @Test
  void bccIsChecked() {
    assertFalse(evaluator.matches(SEND, args("to", List.of("a@example.com"), "bcc", List.of("spy@other.com")), EXAMPLE));
    assertFalse(evaluator.matches("forward_email", args("to", List.of("a@example.com"), "bcc", List.of("spy@other.com")), EXAMPLE));
  }

  /**
   * Anything but a bare address fails closed: a display name, a group, two
   * addresses in one entry, two at signs, an address literal, a trailing dot,
   * an empty part.
   */
  @Test
  void onlyBareAddressesMatch() {
    for (String entry : List.of("Bob <bob@other.com>",
                                "<a@example.com>",
                                "\"a@example.com\" <x@other.com>",
                                "team:a@example.com;",
                                "a@example.com,b@other.com",
                                "a@example.com;b@other.com",
                                "a@other.com@example.com",
                                "a@[192.0.2.1]",
                                "a@example.com.",
                                "@example.com",
                                "a@",
                                "a@example..com",
                                "a&amp;@example.com",
                                "a b@example.com",
                                " ")) {
      assertFalse(evaluator.matches(SEND, args("to", List.of(entry)), EXAMPLE), entry);
    }
  }

  /**
   * Arguments the tool wouldn't read as a list of strings fail closed.
   */
  @Test
  void malformedArgumentsFailClosed() {
    assertFalse(evaluator.matches(SEND, args("to", "a@example.com"), EXAMPLE));
    assertFalse(evaluator.matches(SEND, args("to", List.of(42)), EXAMPLE));
    assertFalse(evaluator.matches(SEND, args("to", Arrays.asList("a@example.com", null)), EXAMPLE));
    assertFalse(evaluator.matches(SEND, args(), EXAMPLE));
    assertFalse(evaluator.matches(SEND, args("to", List.of()), EXAMPLE));
  }

  /**
   * Only the domain kind is understood, and a broken constraint matches
   * nothing.
   */
  @Test
  void unknownOrBrokenConstraintMatchesNothing() {
    Map<String, Object> call = args("to", List.of("a@example.com"));
    assertFalse(evaluator.matches(SEND, call, new McpToolGrantConstraint("recipient", "a@example.com")));
    assertFalse(evaluator.matches(SEND, call, domain("")));
    assertFalse(evaluator.matches(SEND, call, domain("[192.0.2.1]")));
    assertFalse(evaluator.matches(SEND, call, null));
  }

  /**
   * The send tool and the limit read an address with one rule: the tool's
   * bare-address check is the shared one.
   */
  @Test
  void toolAndLimitShareTheAddressRules() {
    assertTrue(EmailAddressRules.isBareAddress("a@example.com"));
    assertFalse(EmailAddressRules.isBareAddress("Bob <a@example.com>"));
    assertEquals("example.com", EmailAddressRules.domainOf(" A@Example.COM "));
    assertNull(EmailAddressRules.domainOf("a@b@example.com"));
  }

  /**
   * @param kv alternating argument names and values
   * @return the arguments as the tool receives them
   */
  private static Map<String, Object> args(Object... kv) {
    Map<String, Object> arguments = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      arguments.put((String) kv[i], kv[i + 1]);
    }
    return arguments;
  }

  /**
   * @param value the domain
   * @return a domain limit
   */
  private static McpToolGrantConstraint domain(String value) {
    return new McpToolGrantConstraint(McpToolGrantConstraint.EMAIL_DOMAIN_KIND, value);
  }

}
