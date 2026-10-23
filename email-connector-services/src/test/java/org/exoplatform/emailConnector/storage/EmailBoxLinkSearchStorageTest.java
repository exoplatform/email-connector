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
package org.exoplatform.emailConnector.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * The "/mail" link picker's search over the mailbox cache (EXO-90715), end to end
 * from the storage to the SHIPPED Liquibase schema: the owner's rows only, the page
 * cut in SQL and capped, the keyword cut and taken literally, and the light mails it
 * answers carrying what the picker shows.
 * <p>
 * Nothing rolls back here ({@link Propagation#NOT_SUPPORTED}, as the sibling storage
 * rigs), so each test works in a mailbox of its own.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailBoxStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailBoxLinkSearchStorageTest {

  @Autowired
  private EmailBoxStorage     emailBoxStorage;

  @Autowired
  private EmailBoxDAO         emailBoxDAO;

  @MockitoBean
  private CategoryLinkService categoryLinkService;

  @MockitoBean
  private FileService         fileService;

  @MockitoBean
  private UploadService       uploadService;

  /**
   * The minimal Spring slice: the mail entities and their repositories, over Boot's
   * auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * The owner's matches come back newest first, as light mails carrying the id,
   * the owner, the subject and the sender; another mailbox holding the same
   * words, and the owner's own draft, trash and junk rows, never do.
   */
  @Test
  void answersTheOwnersMatchesOnly() {
    long older = save("mona", MailFolder.INBOX, "Invoice March", "Contoso Billing,billing@contoso.com", 1_000L);
    long newer = save("mona", MailFolder.SENT, "Re: invoice", "Mona,mona@example.org", 2_000L);
    save("nina", MailFolder.INBOX, "Invoice for nina", "Contoso Billing,billing@contoso.com", 3_000L);
    save("mona", MailFolder.DRAFTS, "Invoice draft", "Mona,mona@example.org", 4_000L);
    save("mona", MailFolder.TRASH, "Invoice deleted", "Contoso Billing,billing@contoso.com", 5_000L);
    save("mona", MailFolder.JUNK, "Invoice junk", "Spam,invoice@spam.test", 6_000L);

    List<Email> found = emailBoxStorage.searchEmailsForLink("mona", "INVOICE", 0, 10);

    assertEquals(List.of(newer, older), found.stream().map(Email::getId).toList());
    Email first = found.get(1);
    assertEquals("mona", first.getUserId());
    assertEquals("Invoice March", first.getSubject());
    assertEquals("Contoso Billing", first.getSender().getName());
    assertEquals("billing@contoso.com", first.getSender().getAddress());
    assertEquals(new Date(1_000L), first.getReceivedDate());
  }

  /**
   * However many are asked for, a page holds {@link EmailBoxStorage#MAX_LINK_RESULTS}
   * at most, and the offset skips the newest.
   */
  @Test
  void capsThePage() {
    for (long i = 1; i <= 25; i++) {
      save("olga", MailFolder.INBOX, "weekly report " + i, "Bob,bob@example.org", i * 1_000L);
    }

    assertEquals(EmailBoxStorage.MAX_LINK_RESULTS, emailBoxStorage.searchEmailsForLink("olga", "report", 0, 500).size());
    List<Email> secondPage = emailBoxStorage.searchEmailsForLink("olga", "report", 20, 20);
    assertEquals(List.of("weekly report 5", "weekly report 4", "weekly report 3", "weekly report 2", "weekly report 1"),
                 secondPage.stream().map(Email::getSubject).toList());
  }

  /**
   * A keyword longer than {@link EmailBoxStorage#MAX_LINK_KEYWORD_LENGTH} is cut to
   * that length rather than refused; a blank keyword, a blank owner or a
   * non-positive limit read nothing.
   */
  @Test
  void cutsTheKeywordAndRefusesTheBlank() {
    String prefix = "x".repeat(EmailBoxStorage.MAX_LINK_KEYWORD_LENGTH);
    long id = save("paula", MailFolder.INBOX, prefix + " tail", "Bob,bob@example.org", 1_000L);

    assertEquals(List.of(id),
                 emailBoxStorage.searchEmailsForLink("paula", prefix + "never in any subject", 0, 10)
                                .stream()
                                .map(Email::getId)
                                .toList());
    assertTrue(emailBoxStorage.searchEmailsForLink("paula", "   ", 0, 10).isEmpty());
    assertTrue(emailBoxStorage.searchEmailsForLink("", "tail", 0, 10).isEmpty());
    assertTrue(emailBoxStorage.searchEmailsForLink("paula", "tail", 0, 0).isEmpty());
  }

  /**
   * The pattern escapes the escape character first, then both wildcards, and lowers
   * the keyword.
   */
  @Test
  void buildsALiteralPattern() {
    assertEquals("%50!% off!!!_now%", EmailBoxStorage.toContainsPattern("  50% OFF!_NOW "));
    assertNull(EmailBoxStorage.toContainsPattern(" "));
    assertNull(EmailBoxStorage.toContainsPattern(null));
  }

  /**
   * Stores one cached message.
   *
   * @param owner the mailbox owner
   * @param folder the {@link MailFolder} discriminator
   * @param subject the subject
   * @param sender the stored {@code name,address} sender
   * @param receivedAt the reception time, in epoch milliseconds
   * @return the row's generated id
   */
  private long save(String owner, String folder, String subject, String sender, long receivedAt) {
    EmailBoxEntity email = new EmailBoxEntity();
    email.setMailRemoteId(receivedAt);
    email.setUserId(owner);
    email.setFolder(folder);
    email.setSubject(subject);
    email.setSender(sender);
    email.setTo("Someone,someone@example.org");
    email.setCc("");
    email.setReceivedDate(new Date(receivedAt));
    email.setBody("body");
    return emailBoxDAO.save(email).getId();
  }
}
