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
package org.exoplatform.emailConnector.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The read the mailbox's "Suggestions" view lists its mails from (EXO-90851), end to end
 * from the storage to the SHIPPED Liquibase schema: the owner's copies of the given
 * Message-IDs outside the excluded folders, newest first, as light mails carrying what
 * the list shows -- the sender read from its stored {@code name,address} form.
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
public class EmailBoxWaitingSuggestionStorageTest {

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
   * The owner's copies come back newest first, as light mails carrying the id, the
   * folder, the Message-ID, the UID, the subject, the sender, the date and the read and
   * starred flags; another owner's copy, an excluded folder's and another Message-ID's
   * never do.
   */
  @Test
  void answersTheOwnersListableCopiesAsLightMails() {
    long older = save("rosa", MailFolder.INBOX, "<a@host>", "Contoso Billing,billing@contoso.com", 1_000L, true, false);
    long newer = save("rosa", "CUSTOM:4", "<b@host>", "Doe, John,john@example.org", 2_000L, false, true);
    save("rosa", MailFolder.JUNK, "<a@host>", "Spam,spam@spam.test", 3_000L, false, false);
    save("rosa", MailFolder.INBOX, "<c@host>", "Other,other@example.org", 4_000L, false, false);
    save("tess", MailFolder.INBOX, "<a@host>", "Contoso Billing,billing@contoso.com", 5_000L, false, false);

    List<Email> found = emailBoxStorage.getListedEmailsByMailHeaderIds("rosa", List.of("<a@host>", "<b@host>"),
                                                                       MailFolder.HIDDEN_FOLDERS);

    assertEquals(List.of(newer, older), found.stream().map(Email::getId).toList());
    Email first = found.get(1);
    assertEquals(MailFolder.INBOX, first.getFolder());
    assertEquals("<a@host>", first.getMailHeaderId());
    assertEquals(1_000L, first.getMailRemoteId());
    assertEquals("Subject 1000", first.getSubject());
    assertEquals("Contoso Billing", first.getSender().getName());
    assertEquals("billing@contoso.com", first.getSender().getAddress());
    assertEquals(new Date(1_000L), first.getReceivedDate());
    assertTrue(first.isRead());
    assertFalse(first.isStarred());
    Email second = found.get(0);
    assertEquals("Doe, John", second.getSender().getName(), "a comma in the name keeps the name whole");
    assertEquals("john@example.org", second.getSender().getAddress());
    assertFalse(second.isRead());
    assertTrue(second.isStarred());
    assertNull(second.getContent(), "no body is read");
  }

  /**
   * A stored sender with no name is its address, named by it; no Message-ID, or no
   * owner, reads nothing.
   */
  @Test
  void aBareAddressIsTheSenderAndNothingIsReadForNoMessageId() {
    save("uma", MailFolder.INBOX, "<d@host>", "bare@example.org", 1_000L, false, false);

    Email found = emailBoxStorage.getListedEmailsByMailHeaderIds("uma", List.of("<d@host>"), MailFolder.HIDDEN_FOLDERS).get(0);

    assertEquals("bare@example.org", found.getSender().getAddress());
    assertEquals("bare@example.org", found.getSender().getName());
    assertEquals(List.of(), emailBoxStorage.getListedEmailsByMailHeaderIds("uma", List.of(), MailFolder.HIDDEN_FOLDERS));
    assertEquals(List.of(), emailBoxStorage.getListedEmailsByMailHeaderIds(" ", List.of("<d@host>"), MailFolder.HIDDEN_FOLDERS));
  }

  /**
   * Stores one cached message.
   *
   * @param owner the mailbox owner
   * @param folder the {@link MailFolder} discriminator
   * @param mailHeaderId its Message-ID
   * @param sender the stored {@code name,address} sender
   * @param receivedAt the reception time, in epoch milliseconds, also its UID and in its subject
   * @param read whether it is read
   * @param starred whether it is starred
   * @return the row's generated id
   */
  private long save(String owner, String folder, String mailHeaderId, String sender, long receivedAt, boolean read, boolean starred) {
    EmailBoxEntity email = new EmailBoxEntity();
    email.setMailRemoteId(receivedAt);
    email.setUserId(owner);
    email.setFolder(folder);
    email.setMailHeaderId(mailHeaderId);
    email.setSubject("Subject " + receivedAt);
    email.setSender(sender);
    email.setTo("Someone,someone@example.org");
    email.setCc("");
    email.setReceivedDate(new Date(receivedAt));
    email.setBody("body");
    email.setRead(read);
    email.setStarred(starred);
    return emailBoxDAO.save(email).getId();
  }
}
