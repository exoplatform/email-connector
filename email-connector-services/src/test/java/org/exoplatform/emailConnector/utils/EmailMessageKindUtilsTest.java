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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.constant.MessageKind;
import org.exoplatform.emailConnector.model.MessageClassification;

/**
 * EXO-91995: bounces, automatic replies and mail a person wrote are told apart from
 * the headers and the MIME structure of real-shaped messages, whatever their wording.
 */
class EmailMessageKindUtilsTest {

  private static final Session SESSION = Session.getInstance(new Properties());

  /** A Postfix bounce: one recipient failed, with its Final-Recipient. */
  @Test
  void aDeliveryStatusReportWithAFailedRecipientIsABounce() throws Exception {
    MessageClassification classification = classify(report("delivery-status", "message/delivery-status", """
        Reporting-MTA: dns; mx.corp.example
        X-Postfix-Queue-ID: 4F1A2

        Final-Recipient: rfc822; gone@prospect.example
        Original-Recipient: rfc822;gone@prospect.example
        Action: failed
        Status: 5.1.1
        Diagnostic-Code: smtp; 550 5.1.1 <gone@prospect.example>: Recipient address rejected
        """, ""));
    assertEquals(MessageKind.BOUNCE, classification.kind());
    assertEquals(List.of("gone@prospect.example"), classification.bouncedRecipients());
  }

  /**
   * A report on two recipients names only the one that failed; the one merely delayed is
   * no bounce of its own.
   */
  @Test
  void onlyTheFailedRecipientsOfAReportAreBounced() throws Exception {
    MessageClassification classification = classify(report("delivery-status", "message/delivery-status", """
        Reporting-MTA: dns; mx.corp.example

        Final-Recipient: rfc822; slow@prospect.example
        Action: delayed
        Status: 4.4.1

        Final-Recipient: RFC822; <Gone@Prospect.example>
        Action: FAILED
        Status: 5.1.1
        """, ""));
    assertEquals(MessageKind.BOUNCE, classification.kind());
    assertEquals(List.of("Gone@Prospect.example"), classification.bouncedRecipients());
  }

  /** A delay notice failed nobody: it is an automatic message, never a bounce. */
  @Test
  void aReportThatFailedNobodyIsAnAutomaticReply() throws Exception {
    MessageClassification classification = classify(report("delivery-status", "message/delivery-status", """
        Reporting-MTA: dns; mx.corp.example

        Final-Recipient: rfc822; slow@prospect.example
        Action: delayed
        Status: 4.4.1
        """, ""));
    assertEquals(MessageKind.AUTO_REPLY, classification.kind());
    assertEquals(List.of(), classification.bouncedRecipients());
  }

  /**
   * A failed recipient without Final-Recipient is read from Original-Recipient; one
   * whose address cannot be read, or names a group, still makes a bounce, with no
   * address for it.
   */
  @Test
  void aFailedRecipientIsReadFromOriginalRecipientOrLeftOut() throws Exception {
    MessageClassification classification = classify(report("delivery-status", "message/delivery-status", """
        Reporting-MTA: dns; mx.corp.example

        Original-Recipient: rfc822; first@prospect.example
        Action: failed

        Final-Recipient: rfc822; not an address
        Action: failed

        Final-Recipient: rfc822; undisclosed-recipients:;
        Action: failed

        Final-Recipient: rfc822; team: a@prospect.example, b@prospect.example;
        Action: failed
        """, ""));
    assertEquals(MessageKind.BOUNCE, classification.kind());
    assertEquals(List.of("first@prospect.example"), classification.bouncedRecipients());

    MessageClassification nameless = classify(report("delivery-status", "message/delivery-status", """
        Reporting-MTA: dns; mx.corp.example

        Final-Recipient: rfc822; not an address
        Action: failed
        """, ""));
    assertEquals(MessageKind.BOUNCE, nameless.kind());
    assertEquals(List.of(), nameless.bouncedRecipients());
  }

  /** The RFC 6533 internationalized report is read like RFC 3464's. */
  @Test
  void aGlobalDeliveryStatusReportIsABounce() throws Exception {
    MessageClassification classification = classify(report("global-delivery-status", "message/global-delivery-status", """
        Reporting-MTA: dns; mx.corp.example

        Final-Recipient: utf-8; gone@prospect.example
        Action: failed
        Status: 5.1.1
        """, ""));
    assertEquals(MessageKind.BOUNCE, classification.kind());
    assertEquals(List.of("gone@prospect.example"), classification.bouncedRecipients());
  }

  /**
   * An Exim bounce is plain text in the server's language; only X-Failed-Recipients
   * says which addresses failed, and that is enough.
   */
  @Test
  void anXFailedRecipientsHeaderMakesABounce() throws Exception {
    MessageClassification classification = classify(message("""
        From: Mail Delivery System <Mailer-Daemon@mx.example>
        To: alice@corp.example
        Subject: Message non distribuable
        X-Failed-Recipients: gone@prospect.example, Left <left@prospect.example>, sales: c@prospect.example;
        Auto-Submitted: auto-replied
        Content-Type: text/plain; charset=utf-8

        Ce message n'a pas pu etre remis.
        """));
    assertEquals(MessageKind.BOUNCE, classification.kind());
    assertEquals(List.of("gone@prospect.example", "left@prospect.example"), classification.bouncedRecipients());
  }

  /** A report whose status part cannot be found falls back on X-Failed-Recipients. */
  @Test
  void aReportWithoutStatusPartFallsBackOnTheHeader() throws Exception {
    MessageClassification classification = classify(report("delivery-status", "text/plain", "no status here", """
        X-Failed-Recipients: gone@prospect.example
        """));
    assertEquals(MessageKind.BOUNCE, classification.kind());
    assertEquals(List.of("gone@prospect.example"), classification.bouncedRecipients());
  }

  /**
   * Out-of-office replies, from the RFC 3834 header and the responders' own
   * conventions, whatever the case of their values.
   */
  @Test
  void outOfOfficeRepliesAreAutomaticReplies() throws Exception {
    for (String header : List.of("Auto-Submitted: auto-replied",
                                 "Auto-Submitted: Auto-Generated",
                                 "Auto-Submitted: auto-replied; owner-email=\"bob@prospect.example\"",
                                 "X-Autoreply: yes",
                                 "X-Autorespond: Vacation",
                                 "Precedence: auto_reply")) {
      assertEquals(MessageKind.AUTO_REPLY, classify(plain(header)).kind(), header);
    }
  }

  /**
   * A message a person typed stays normal: "Auto-Submitted: no" says so, and a mailing
   * list's Precedence and List headers say nothing about who wrote the message it relays.
   */
  @Test
  void mailAPersonWroteIsNormalEvenRelayedByAList() throws Exception {
    for (String header : List.of("Auto-Submitted: no",
                                 "X-Autoreply: no",
                                 "Precedence: list\nList-Id: <team.lists.example>\nList-Post: <mailto:team@lists.example>",
                                 "Precedence: bulk\nList-Id: <team.lists.example>",
                                 "Precedence: junk",
                                 "X-Mailer: Thunderbird")) {
      MessageClassification classification = classify(plain(header));
      assertEquals(MessageKind.NORMAL, classification.kind(), header);
      assertEquals(List.of(), classification.bouncedRecipients(), header);
    }
  }

  /**
   * Only a report that declares itself a delivery status is read as one: a report of
   * another type is no bounce, whatever part it carries.
   */
  @Test
  void aReportOfAnotherTypeIsNotReadAsADeliveryStatus() throws Exception {
    MessageClassification classification = classify(report("disposition-notification", "message/delivery-status", """
        Reporting-MTA: dns; mx.corp.example

        Final-Recipient: rfc822; gone@prospect.example
        Action: failed
        """, ""));
    assertEquals(MessageKind.AUTO_REPLY, classification.kind());
    assertEquals(List.of(), classification.bouncedRecipients());
  }

  /**
   * A bounce is trusted only with the empty envelope sender mail servers send it with: a
   * report, or an X-Failed-Recipients header, from a sender address is no bounce, and
   * names no address to mark invalid. A missing Return-Path is tolerated.
   */
  @Test
  void aBounceFromASenderAddressIsNotTrusted() throws Exception {
    String status = """
        Reporting-MTA: dns; mx.corp.example

        Final-Recipient: rfc822; customer@prospect.example
        Action: failed
        """;
    MessageClassification forged = classify(report("delivery-status", "message/delivery-status", status,
                                                    "Return-Path: <someone@attacker.example>\n"));
    assertEquals(MessageKind.AUTO_REPLY, forged.kind());
    assertEquals(List.of(), forged.bouncedRecipients());

    MessageClassification genuine = classify(report("delivery-status", "message/delivery-status", status, "Return-Path: < >\n"));
    assertEquals(MessageKind.BOUNCE, genuine.kind());
    assertEquals(List.of("customer@prospect.example"), genuine.bouncedRecipients());

    MessageClassification forgedHeader = classify(plain("Return-Path: <bob@prospect.example>\nX-Failed-Recipients: customer@prospect.example"));
    assertEquals(MessageKind.AUTO_REPLY, forgedHeader.kind());
    assertEquals(List.of(), forgedHeader.bouncedRecipients());
  }

  /**
   * A bounce in no standard form (qmail's plain text) comes with the empty envelope
   * sender: it is never taken for a person's reply. A person's mail names its sender.
   */
  @Test
  void anUnrecognisedMessageWithTheEmptySenderIsAnAutomaticReply() throws Exception {
    MessageClassification qmail = classify(message("""
        Return-Path: <>
        From: MAILER-DAEMON@mx.prospect.example
        To: alice@corp.example
        Subject: failure notice
        Content-Type: text/plain

        Hi. This is the qmail-send program at mx.prospect.example.
        I'm afraid I wasn't able to deliver your message to the following addresses.
        <gone@prospect.example>:
        Sorry, no mailbox here by that name.
        """));
    assertEquals(MessageKind.AUTO_REPLY, qmail.kind());
    assertEquals(MessageKind.NORMAL, classify(plain("Return-Path: <bob@prospect.example>")).kind());
  }

  /**
   * A report that failed a recipient it names in no readable form takes the address from
   * X-Failed-Recipients when the server wrote one.
   */
  @Test
  void aFailedRecipientWithoutAddressFallsBackOnTheHeader() throws Exception {
    MessageClassification classification = classify(report("delivery-status", "message/delivery-status", """
        Reporting-MTA: dns; mx.corp.example

        Final-Recipient: x400; not readable
        Action: failed
        """, "X-Failed-Recipients: gone@prospect.example\n"));
    assertEquals(MessageKind.BOUNCE, classification.kind());
    assertEquals(List.of("gone@prospect.example"), classification.bouncedRecipients());
  }

  /** A read receipt is no bounce, though it is a multipart/report too. */
  @Test
  void aReadReceiptIsAnAutomaticReply() throws Exception {
    MessageClassification classification = classify(report("disposition-notification", "message/disposition-notification", """
        Reporting-UA: prospect.example; Thunderbird
        Final-Recipient: rfc822; bob@prospect.example
        Disposition: manual-action/MDN-sent-manually; displayed
        """, ""));
    assertEquals(MessageKind.AUTO_REPLY, classification.kind());
    assertEquals(List.of(), classification.bouncedRecipients());
  }

  /** A message that cannot be read is left unclassified rather than guessed. */
  @Test
  void anUnreadableMessageIsLeftUnclassified() throws Exception {
    Message message = mock(Message.class);
    when(message.getHeader("X-Failed-Recipients")).thenThrow(new MessagingException("connection lost"));
    assertNull(EmailMessageKindUtils.classify(message));
  }

  /**
   * The failed addresses fit their column whole: the ones that do not are left out,
   * never cut; and they read back as they were written.
   */
  @Test
  void theFailedAddressesAreJoinedWholeAndSplitBack() {
    assertNull(EmailMessageKindUtils.joinRecipients(null, 100));
    assertNull(EmailMessageKindUtils.joinRecipients(List.of(), 100));
    assertEquals("a@x.example, b@x.example", EmailMessageKindUtils.joinRecipients(List.of("a@x.example", "b@x.example"), 24));
    assertEquals("a@x.example", EmailMessageKindUtils.joinRecipients(List.of("a@x.example", "b@x.example"), 23));
    assertNull(EmailMessageKindUtils.joinRecipients(List.of("a@x.example"), 10));
    assertEquals(List.of("a@x.example", "b@x.example"), EmailMessageKindUtils.splitRecipients("a@x.example, b@x.example"));
    assertEquals(List.of(), EmailMessageKindUtils.splitRecipients(null));
    assertEquals(List.of(), EmailMessageKindUtils.splitRecipients(" "));
    assertEquals(List.of("\"a,b\"@x.example", "c@x.example"), EmailMessageKindUtils.splitRecipients("\"a,b\"@x.example, c@x.example"));
  }

  /**
   * Classifies a message.
   *
   * @param message the message
   * @return its classification, which every case here expects to exist
   */
  private static MessageClassification classify(MimeMessage message) {
    MessageClassification classification = EmailMessageKindUtils.classify(message);
    if (classification == null) {
      throw new AssertionError("the message was expected to be classified");
    }
    return classification;
  }

  /**
   * A plain text message to a person, with extra header lines.
   *
   * @param headers the extra header lines
   * @return the message
   * @throws MessagingException if it cannot be parsed
   */
  private static MimeMessage plain(String headers) throws MessagingException {
    return message("From: Bob <bob@prospect.example>\nTo: alice@corp.example\nSubject: Re: our offer\n" + headers
        + "\nContent-Type: text/plain; charset=utf-8\n\nThanks, let's talk on Monday.\n");
  }

  /**
   * A {@code multipart/report} from a mail server: a human-readable part, then the
   * status part, then the returned headers.
   *
   * @param reportType the {@code report-type} parameter
   * @param statusType the content type of the second part
   * @param status the second part's text
   * @param extraHeaders more header lines of the report, each ending with a newline
   * @return the message
   * @throws MessagingException if it cannot be parsed
   */
  private static MimeMessage report(String reportType,
                                    String statusType,
                                    String status,
                                    String extraHeaders) throws MessagingException {
    return message("From: MAILER-DAEMON@mx.corp.example (Mail Delivery System)\n"
        + "To: alice@corp.example\n"
        + "Subject: Undelivered Mail Returned to Sender\n"
        + "Auto-Submitted: auto-replied\n"
        + extraHeaders
        + "MIME-Version: 1.0\n"
        + "Content-Type: multipart/report; report-type=" + reportType + "; boundary=\"B1\"\n"
        + "\n"
        + "--B1\n"
        + "Content-Type: text/plain; charset=us-ascii\n"
        + "\n"
        + "Votre message n'a pas pu etre remis.\n"
        + "\n"
        + "--B1\n"
        + "Content-Type: " + statusType + "\n"
        + "\n"
        + status
        + "\n"
        + "--B1\n"
        + "Content-Type: text/rfc822-headers\n"
        + "\n"
        + "Subject: our offer\n"
        + "\n"
        + "--B1--\n");
  }

  /**
   * Parses a raw message, its lines ending in CRLF as on the wire.
   *
   * @param raw the message, lines ending with a newline
   * @return the message
   * @throws MessagingException if it cannot be parsed
   */
  private static MimeMessage message(String raw) throws MessagingException {
    String wire = raw.replace("\r\n", "\n").replace("\n", "\r\n");
    return new MimeMessage(SESSION, new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
  }
}
