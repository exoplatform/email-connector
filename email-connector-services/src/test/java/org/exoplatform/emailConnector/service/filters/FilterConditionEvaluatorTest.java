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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.ServerRule.Condition;
import org.exoplatform.emailConnector.service.filters.FilterConditionEvaluator.Result;

/**
 * The evaluator reads the vocabulary the way the server does: case ignored, an address
 * condition true on one address, {@code NOT_CONTAINS} true when none contains, a domain
 * matching its subdomains and never a longer name, and an unknown part of the mail
 * leaving the answer undecided -- in "all" and in "any" alike.
 */
class FilterConditionEvaluatorTest {

  /**
   * The address, text and flag conditions.
   */
  @Test
  void conditionsReadTheMailAsTheServerDoes() {
    FilterMail mail = mail("Boss <ignored>", "boss@Sales.ACME.com", "Invoice 42 due", "<p>Pay the <b>invoice</b></p>", true);

    assertEquals(Result.TRUE, one(new Condition("FROM", "MATCHES_DOMAIN", null, "acme.com"), mail));
    assertEquals(Result.FALSE, one(new Condition("FROM", "MATCHES_DOMAIN", null, "me.com"), mail), "not a longer name");
    assertEquals(Result.TRUE, one(new Condition("SUBJECT", "STARTS_WITH", null, "INVOICE"), mail));
    assertEquals(Result.TRUE, one(new Condition("TO", "EQUALS", null, "ME@corp.com"), mail));
    assertEquals(Result.TRUE, one(new Condition("ANY_RECIPIENT", "ENDS_WITH", null, "@partner.org"), mail), "a Cc address");
    assertEquals(Result.FALSE, one(new Condition("ANY_RECIPIENT", "NOT_CONTAINS", null, "corp.com"), mail), "one recipient contains it");
    assertEquals(Result.TRUE, one(new Condition("BODY", "CONTAINS", null, "pay the invoice"), mail), "the body's text, not its HTML");
    assertEquals(Result.TRUE, one(new Condition("SUBJECT_OR_BODY", "CONTAINS", null, "due"), mail));
    assertEquals(Result.FALSE, one(new Condition("SUBJECT_OR_BODY", "NOT_CONTAINS", null, "invoice"), mail));
    assertEquals(Result.TRUE, one(new Condition("IS_LIST", "IS_TRUE", null, null), mail));
    assertEquals(Result.TRUE, one(new Condition("IS_AUTOMATED", "IS_FALSE", null, null), mail));
    assertEquals(Result.FALSE, one(new Condition("HAS_ATTACHMENT", "IS_TRUE", null, null), mail));
  }

  /**
   * A header or a size eXo does not know leaves the answer undecided, unless the other
   * conditions decide it; known, they compare.
   */
  @Test
  void anUnknownPartLeavesTheAnswerUndecided() {
    FilterMail unknown = mail("", "a@acme.com", "Hi", "", false);
    Condition header = new Condition("HEADER", "EQUALS", "x-priority", "1");
    Condition acme = new Condition("FROM", "MATCHES_DOMAIN", null, "acme.com");
    Condition other = new Condition("FROM", "MATCHES_DOMAIN", null, "other.org");

    assertEquals(Result.UNKNOWN, FilterConditionEvaluator.evaluate(List.of(acme, header), true, unknown));
    assertEquals(Result.FALSE, FilterConditionEvaluator.evaluate(List.of(other, header), true, unknown), "decided by the other one");
    assertEquals(Result.TRUE, FilterConditionEvaluator.evaluate(List.of(header, acme), false, unknown));
    assertEquals(Result.UNKNOWN, FilterConditionEvaluator.evaluate(List.of(other, header), false, unknown));
    assertEquals(Result.FALSE, FilterConditionEvaluator.evaluate(List.of(), true, unknown), "no condition matches nothing");

    Email email = new Email();
    email.setSender(sender("a@acme.com"));
    FilterMail known = new EmailFilterMail(email,
                                           null,
                                           Set.of(),
                                           name -> Map.of("x-priority", List.of("1")).getOrDefault(name, List.of()),
                                           () -> 120L);
    assertEquals(Result.TRUE, FilterConditionEvaluator.evaluate(List.of(acme, header), true, known));
    assertEquals(Result.TRUE, FilterConditionEvaluator.evaluate(List.of(new Condition("MESSAGE_SIZE", "GT", null, "100")), true, known));
    assertEquals(Result.FALSE, FilterConditionEvaluator.evaluate(List.of(new Condition("MESSAGE_SIZE", "LT", null, "100")), true, known));
  }

  /**
   * One condition.
   *
   * @param condition the condition
   * @param mail the mail
   * @return its answer
   */
  private static Result one(Condition condition, FilterMail mail) {
    return FilterConditionEvaluator.evaluate(condition, mail);
  }

  /**
   * A cached mail, whole, to me and Cc a partner.
   *
   * @param name unused, the sender's display name
   * @param from the sender
   * @param subject the subject
   * @param html the body, HTML
   * @param list whether it comes from a list
   * @return the mail
   */
  private static FilterMail mail(String name, String from, String subject, String html, boolean list) {
    Email email = new Email();
    email.setSender(sender(from));
    email.setSubject(subject);
    email.setTo(List.of(recipient("me@corp.com")));
    email.setCc(List.of(recipient("buyer@partner.org")));
    EmailContent content = new EmailContent(html, null, List.of());
    content.setHtml(true);
    email.setContent(content);
    email.setHasListId(list);
    return new EmailFilterMail(email, null, Set.of(), null, null);
  }

  /**
   * A sender.
   *
   * @param address the address
   * @return the sender
   */
  private static EmailSender sender(String address) {
    EmailSender sender = new EmailSender();
    sender.setAddress(address);
    return sender;
  }

  /**
   * A recipient.
   *
   * @param address the address
   * @return the recipient
   */
  private static EmailRecipient recipient(String address) {
    EmailRecipient recipient = new EmailRecipient();
    recipient.setAddress(address);
    return recipient;
  }
}
