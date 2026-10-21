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
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.search.SearchTerm;
import javax.mail.util.ByteArrayDataSource;

import org.junit.jupiter.api.Test;

import com.sun.mail.iap.Protocol;
import com.sun.mail.imap.protocol.SearchSequence;

import org.exoplatform.emailConnector.model.EmailSearchCriteria;

/**
 * The criteria of the mailbox's advanced search (EXO-90838) as the mail server receives
 * them: each term built by {@link EmailBoxService#buildEmailSearchTerm(EmailSearchCriteria, Date)}
 * is written out by JavaMail's own IMAP {@code SEARCH} serializer, the code that writes
 * the command on the wire, so what is asserted is the command text and not the shape
 * of a Java object. Whether a given server answers {@code BODY} from an index is not
 * what this shows.
 */
public class EmailSearchCriteriaTermTest {

  /**
   * The words are searched in the subject or the body, the attachment criterion never
   * reaches the server (it is eXo's own, examined from the matches' structure), and the
   * days are {@code SINCE} (that day included) and {@code BEFORE} (that day excluded),
   * each written as the very day it was given.
   *
   * @throws Exception when the term cannot be written
   */
  @Test
  void theAdvancedCriteriaReachTheServerAsOneSearchCommand() throws Exception {
    EmailSearchCriteria criteria = new EmailSearchCriteria();
    criteria.setWords(" budget q3 ");
    criteria.setAttachmentsOnly(true);
    criteria.setAfter(LocalDate.of(2026, 10, 1));
    criteria.setBefore(LocalDate.of(2026, 10, 31));

    assertEquals("OR SUBJECT \"budget q3\" BODY \"budget q3\" SINCE 1-Oct-2026 BEFORE 31-Oct-2026",
                 imapSearch(EmailBoxService.buildEmailSearchTerm(criteria, null)));
    EmailSearchCriteria attachmentsAlone = new EmailSearchCriteria();
    attachmentsAlone.setAttachmentsOnly(true);
    assertNull(EmailBoxService.buildEmailSearchTerm(attachmentsAlone, null), "no term at all for the attachment criterion");
  }

  /**
   * Every criterion at once, the earlier ones included: one AND of all of them, in the
   * order the builder lists them.
   *
   * @throws Exception when the term cannot be written
   */
  @Test
  void everyCriterionIsOneMoreTermOfTheSameSearch() throws Exception {
    EmailSearchCriteria criteria = new EmailSearchCriteria();
    criteria.setQuery("report");
    criteria.setFrom("carol");
    criteria.setTo("dave");
    criteria.setWords("budget");
    criteria.setUnreadOnly(true);
    criteria.setFavoritesOnly(true);
    criteria.setAttachmentsOnly(true);
    criteria.setAfter(LocalDate.of(2026, 3, 29));
    criteria.setBefore(LocalDate.of(2026, 3, 30));

    assertEquals("OR SUBJECT report FROM report FROM carol OR TO dave CC dave OR SUBJECT budget BODY budget UNSEEN FLAGGED"
        + " SINCE 29-Mar-2026 BEFORE 30-Mar-2026",
                 imapSearch(EmailBoxService.buildEmailSearchTerm(criteria, null)));
  }

  /**
   * The search box's own text never searches the body: only the words of the advanced
   * search do, since a body search on a server without an index scans the folder.
   *
   * @throws Exception when the term cannot be written
   */
  @Test
  void theSearchBoxTextSearchesTheSubjectAndTheSenderOnly() throws Exception {
    EmailSearchCriteria criteria = new EmailSearchCriteria();
    criteria.setQuery("budget");

    assertEquals("OR SUBJECT budget FROM budget", imapSearch(EmailBoxService.buildEmailSearchTerm(criteria, null)));
    assertNull(EmailBoxService.buildEmailSearchTerm(new EmailSearchCriteria(), null), "no criterion, no search");
  }

  /**
   * A day's bound is its first instant in the server's zone, whatever the day -- the
   * day the clocks change included -- and JavaMail writes that instant back as the same
   * day.
   */
  @Test
  void aDayBoundIsTheDaysFirstInstantInTheServersZone() {
    for (LocalDate day = LocalDate.of(2026, 1, 1); day.getYear() == 2026; day = day.plusDays(1)) {
      Date bound = EmailBoxService.startOfServerDay(day);
      assertEquals(day.atStartOfDay(ZoneId.systemDefault()).toInstant(), bound.toInstant());
      Calendar calendar = new GregorianCalendar();
      calendar.setTime(bound);
      assertEquals(day,
                   LocalDate.of(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH)),
                   "the day JavaMail writes for " + day);
    }
    assertNull(EmailBoxService.startOfServerDay(null));
  }

  /**
   * The criteria a search cannot answer are refused with their code: a day that is not
   * one, a range that ends where or before it starts, a negative age window, nothing at
   * all.
   * A one-day range is the day before the next one.
   */
  @Test
  void anUnanswerableSearchIsRefusedWithItsCode() {
    EmailSearchCriteria sameDay = new EmailSearchCriteria();
    sameDay.setAfter(LocalDate.of(2026, 10, 2));
    sameDay.setBefore(LocalDate.of(2026, 10, 2));
    assertEquals("emailConnector.search.invalidDateRange",
                 assertThrows(IllegalArgumentException.class, () -> EmailBoxService.validateSearchCriteria(sameDay)).getMessage());
    EmailSearchCriteria reversed = new EmailSearchCriteria();
    reversed.setAfter(LocalDate.of(2026, 10, 3));
    reversed.setBefore(LocalDate.of(2026, 10, 2));
    assertEquals("emailConnector.search.invalidDateRange",
                 assertThrows(IllegalArgumentException.class, () -> EmailBoxService.validateSearchCriteria(reversed)).getMessage());
    EmailSearchCriteria oneDay = new EmailSearchCriteria();
    oneDay.setAfter(LocalDate.of(2026, 10, 2));
    oneDay.setBefore(LocalDate.of(2026, 10, 3));
    assertDoesNotThrow(() -> EmailBoxService.validateSearchCriteria(oneDay));

    EmailSearchCriteria notADay = new EmailSearchCriteria();
    notADay.setQuery("report");
    notADay.setInvalidDay(true);
    assertEquals("emailConnector.search.invalidDate",
                 assertThrows(IllegalArgumentException.class, () -> EmailBoxService.validateSearchCriteria(notADay)).getMessage());

    EmailSearchCriteria negative = new EmailSearchCriteria();
    negative.setSinceDays(-1);
    assertEquals("emailConnector.search.invalidSinceDays",
                 assertThrows(IllegalArgumentException.class, () -> EmailBoxService.validateSearchCriteria(negative)).getMessage());
    assertEquals("emailConnector.search.criteriaRequired",
                 assertThrows(IllegalArgumentException.class,
                              () -> EmailBoxService.validateSearchCriteria(new EmailSearchCriteria())).getMessage());
    EmailSearchCriteria attachmentsAlone = new EmailSearchCriteria();
    attachmentsAlone.setAttachmentsOnly(true);
    assertDoesNotThrow(() -> EmailBoxService.validateSearchCriteria(attachmentsAlone), "one criterion is enough");
  }

  /**
   * A server match has an attachment exactly when eXo would write an attachment row for
   * it, read from its MIME structure as the sync's extractor reads it: a file in a
   * {@code multipart/mixed}, or in a nested multipart, an image sent with no disposition
   * (eXo lists it), are attachments; the text and HTML bodies, an inline image of the
   * body, a non-image part marked inline and a single-part message are not.
   *
   * @throws Exception when a message cannot be built
   */
  @Test
  void aServerMatchHasAnAttachmentWhenEXoWouldListOne() throws Exception {
    assertTrue(EmailBoxService.hasStoredAttachment(message(multipart("mixed", text("plain"), file("application/pdf", Part.ATTACHMENT))),
                                                   "alice"));
    assertTrue(EmailBoxService.hasStoredAttachment(message(multipart("mixed",
                                                                     multipart("alternative", text("plain"), text("html")),
                                                                     file("application/pdf", null))),
                                                   "alice"),
               "a file with no disposition, beside a nested body");
    assertTrue(EmailBoxService.hasStoredAttachment(message(multipart("related", text("html"), file("image/png", null))), "alice"),
               "an image with no disposition is listed by eXo");
    assertTrue(EmailBoxService.hasStoredAttachment(message(multipart("mixed",
                                                                     text("plain"),
                                                                     multipart("related", text("html"), file("image/png", null)))),
                                                   "alice"),
               "a file in a nested multipart");
    assertFalse(EmailBoxService.hasStoredAttachment(message(multipart("mixed",
                                                                      text("html"),
                                                                      multipart("appledouble",
                                                                                file("application/applefile", null),
                                                                                file("application/pdf", null)))),
                                                    "alice"),
                "a nested level with no text: eXo keeps none of its files");
    assertFalse(EmailBoxService.hasStoredAttachment(message(multipart("alternative", text("plain"), text("html"))), "alice"));
    assertFalse(EmailBoxService.hasStoredAttachment(message(multipart("related", text("html"), file("image/png", Part.INLINE))),
                                                    "alice"),
                "an inline image is the body's");
    assertFalse(EmailBoxService.hasStoredAttachment(message(multipart("mixed", text("plain"), file("application/pdf", Part.INLINE))),
                                                    "alice"),
                "a part marked inline is not listed by eXo");
    MimeMessage single = new MimeMessage((Session) null);
    single.setText("just text");
    single.saveChanges();
    assertFalse(EmailBoxService.hasStoredAttachment(single, "alice"));
  }

  /**
   * A message built and re-read from its bytes, as a structure a server describes.
   *
   * @param content its multipart
   * @return the message
   * @throws Exception when it cannot be built
   */
  static MimeMessage message(MimeMultipart content) throws Exception {
    MimeMessage message = new MimeMessage((Session) null);
    message.setContent(content);
    message.saveChanges();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    message.writeTo(bytes);
    return new MimeMessage((Session) null, new ByteArrayInputStream(bytes.toByteArray()));
  }

  /**
   * A multipart of a subtype holding parts.
   *
   * @param subtype the subtype
   * @param parts its parts: body parts or nested multiparts
   * @return the multipart
   * @throws Exception when it cannot be built
   */
  static MimeMultipart multipart(String subtype, Object... parts) throws Exception {
    MimeMultipart multipart = new MimeMultipart(subtype);
    for (Object part : parts) {
      if (part instanceof MimeMultipart nested) {
        MimeBodyPart holder = new MimeBodyPart();
        holder.setContent(nested);
        multipart.addBodyPart(holder);
      } else {
        multipart.addBodyPart((MimeBodyPart) part);
      }
    }
    return multipart;
  }

  /**
   * A text body part.
   *
   * @param subtype plain or html
   * @return the part
   * @throws Exception when it cannot be built
   */
  static MimeBodyPart text(String subtype) throws Exception {
    MimeBodyPart part = new MimeBodyPart();
    part.setText("the text", "UTF-8", subtype);
    return part;
  }

  /**
   * A file part.
   *
   * @param mimeType its type
   * @param disposition its disposition, null for none
   * @return the part
   * @throws Exception when it cannot be built
   */
  static MimeBodyPart file(String mimeType, String disposition) throws Exception {
    MimeBodyPart part = new MimeBodyPart();
    part.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[] { 1, 2, 3 }, mimeType)));
    part.setHeader("Content-Type", mimeType);
    if (disposition != null) {
      part.setDisposition(disposition);
      part.setFileName("file");
    }
    return part;
  }

  /**
   * Writes a search term the way JavaMail sends it to an IMAP server, after
   * {@code SEARCH}: through {@link SearchSequence} and the protocol's own output.
   *
   * @param term the term
   * @return the command's arguments, as sent
   * @throws Exception when the term cannot be written
   */
  private static String imapSearch(SearchTerm term) throws Exception {
    ByteArrayOutputStream wire = new ByteArrayOutputStream();
    CapturingProtocol protocol = new CapturingProtocol(new PrintStream(wire, true, StandardCharsets.US_ASCII));
    new SearchSequence().generateSequence(term, null).write(protocol);
    protocol.flush();
    return wire.toString(StandardCharsets.US_ASCII).trim();
  }

  /**
   * A protocol on no connection, whose output is the stream it is given: JavaMail's own
   * constructor for that use.
   */
  private static final class CapturingProtocol extends Protocol {

    /**
     * Opens the protocol on an output stream.
     *
     * @param out where the command is written
     * @throws IOException never, on these streams
     */
    CapturingProtocol(PrintStream out) throws IOException {
      super(new ByteArrayInputStream(new byte[0]), out, new Properties(), false);
    }

    /**
     * Flushes what was written to the output stream.
     *
     * @throws IOException never, on these streams
     */
    void flush() throws IOException {
      getOutputStream().flush();
    }
  }
}
