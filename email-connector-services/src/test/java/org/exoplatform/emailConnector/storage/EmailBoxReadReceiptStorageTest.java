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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ReadReceiptState;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * The read-receipt fields through the real storage, over the schema the changelog
 * builds (EXO-90435): mapped both ways, kept by every draft save, and the claim that
 * answers a request at most once, across the cached copies of one message.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailBoxStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EmailBoxReadReceiptStorageTest {

  private static final String USER = "alice";

  @Autowired
  private EmailBoxStorage     emailBoxStorage;

  @Autowired
  private PlatformTransactionManager transactionManager;

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

  /** A synced request is stored and read back whole, as the reader needs it. */
  @Test
  void aReceivedRequestIsStoredAndReadBack() {
    Email incoming = incoming("<in@partner.example>", MailFolder.INBOX, 1L);
    Email created = emailBoxStorage.createEmail(incoming);

    Email read = read(created.getId(), USER, "alice@corp.example");
    assertTrue(read.isReadReceiptRequested());
    assertEquals("bob@partner.example", read.getReadReceiptTo());
    assertTrue(read.isReadReceiptReturnPathMatch());
    assertNull(read.getReadReceiptState());
  }

  /**
   * A draft keeps the author's choice across saves -- the first save creates the row,
   * every later one mutates it column by column -- and unchecking it is saved too.
   */
  @Test
  void aDraftKeepsTheReadReceiptChoiceAcrossSaves() {
    Email first = draft(1L, true);
    assertTrue(emailBoxStorage.saveDraft(first).isReadReceiptRequested());
    assertTrue(emailBoxStorage.saveDraft(draft(2L, true)).isReadReceiptRequested());
    assertFalse(emailBoxStorage.saveDraft(draft(3L, false)).isReadReceiptRequested());
    assertFalse(emailBoxStorage.getDraftByLocalId(USER, "draft-rr").isReadReceiptRequested());
  }

  /**
   * The claim answers the message once, on every cached copy; a copy answered
   * elsewhere (the sync mirroring another client) blocks the whole claim; the release
   * gives it back.
   */
  @Test
  void theClaimAnswersTheMessageOnceOnEveryCopy() {
    Email inbox = emailBoxStorage.createEmail(incoming("<once@partner.example>", MailFolder.INBOX, 1L));
    Email allMail = emailBoxStorage.createEmail(incoming("<once@partner.example>", MailFolder.ALL_MAIL, 9L));

    assertTrue(emailBoxStorage.claimReadReceipt(USER, inbox, ReadReceiptState.SENT));
    assertFalse(emailBoxStorage.claimReadReceipt(USER, inbox, ReadReceiptState.SENT), "at most once");
    assertEquals(ReadReceiptState.SENT, read(allMail.getId(), USER, null).getReadReceiptState());
    emailBoxStorage.releaseReadReceipt(USER, inbox, ReadReceiptState.SENT);
    assertNull(read(allMail.getId(), USER, null).getReadReceiptState());

    Email answeredElsewhere = emailBoxStorage.createEmail(incoming("<twice@partner.example>", MailFolder.INBOX, 2L));
    Email archived = emailBoxStorage.createEmail(incoming("<twice@partner.example>", MailFolder.ARCHIVE, 3L));
    emailBoxStorage.markReadReceiptsAnswered(USER, MailFolder.ARCHIVE, List.of(3L), List.of());
    assertEquals(ReadReceiptState.SENT, read(archived.getId(), USER, null).getReadReceiptState());
    assertFalse(emailBoxStorage.claimReadReceipt(USER, answeredElsewhere, ReadReceiptState.IGNORED),
                "another copy was answered: the message was");
    assertNull(read(answeredElsewhere.getId(), USER, null).getReadReceiptState());

    Email inboxCopy = emailBoxStorage.createEmail(incoming("<mirrored@partner.example>", MailFolder.INBOX, 6L));
    emailBoxStorage.createEmail(incoming("<mirrored@partner.example>", MailFolder.ALL_MAIL, 16L));
    emailBoxStorage.markReadReceiptsAnswered(USER, MailFolder.ALL_MAIL, List.of(16L), List.of("<mirrored@partner.example>"));
    assertEquals(ReadReceiptState.SENT, read(inboxCopy.getId(), USER, null).getReadReceiptState(),
                 "the sync's mirror answers every copy of the message");

    Email anonymous = emailBoxStorage.createEmail(incoming(null, MailFolder.INBOX, 4L));
    assertTrue(emailBoxStorage.claimReadReceipt(USER, anonymous, ReadReceiptState.IGNORED), "by its id");
    assertFalse(emailBoxStorage.claimReadReceipt(USER, anonymous, ReadReceiptState.IGNORED));
  }

  /** The light sync view hands the reconcile the request and its answer. */
  @Test
  void theSyncViewCarriesTheRequest() {
    Email created = emailBoxStorage.createEmail(incoming("<view@partner.example>", MailFolder.INBOX, 5L));
    emailBoxStorage.claimReadReceipt(USER, created, ReadReceiptState.IGNORED);

    Email light = emailBoxStorage.getSyncEmails(USER, MailFolder.INBOX)
                                 .stream()
                                 .filter(email -> email.getMailRemoteId() == 5L)
                                 .findFirst()
                                 .orElseThrow();
    assertTrue(light.isReadReceiptRequested());
    assertEquals(ReadReceiptState.IGNORED, light.getReadReceiptState());
  }

  /**
   * A message read by its id, in a transaction as the service reads it.
   *
   * @param id its technical id
   * @param userId its owner
   * @param userEmail the owner's address, may be null
   * @return the message
   */
  private Email read(long id, String userId, String userEmail) {
    return new TransactionTemplate(transactionManager).execute(status -> emailBoxStorage.getEmailById(id, userId, userEmail));
  }

  /**
   * A received message asking for a receipt.
   *
   * @param messageId its Message-ID, may be null
   * @param folder its folder
   * @param uid its UID
   * @return the message
   */
  private Email incoming(String messageId, String folder, long uid) {
    Email email = new Email();
    email.setUserId(USER);
    email.setFolder(folder);
    email.setMailRemoteId(uid);
    email.setMailHeaderId(messageId);
    email.setSubject("Quarterly figures");
    email.setSender(new EmailSender("Bob", "bob@partner.example", null, null));
    email.setTo(List.of(new EmailRecipient("Alice", "alice@corp.example", null, false)));
    email.setContent(new EmailContent("figures", null, null));
    email.setReceivedDate(new Date());
    email.setReadReceiptRequested(true);
    email.setReadReceiptTo("bob@partner.example");
    email.setReadReceiptReturnPathMatch(true);
    return email;
  }

  /**
   * A revision of one draft.
   *
   * @param revision the revision
   * @param requested whether it asks for a read receipt
   * @return the draft
   */
  private Email draft(long revision, boolean requested) {
    Date now = new Date();
    Email draft = new Email();
    draft.setUserId(USER);
    draft.setFolder(MailFolder.DRAFTS);
    draft.setMailHeaderId("<draft-rr@corp.example>");
    draft.setSender(new EmailSender("Alice", "alice@corp.example", null, null));
    draft.setSubject("Half a sentence");
    draft.setContent(new EmailContent("text", null, null));
    draft.setReceivedDate(now);
    draft.setRead(true);
    draft.setDraftLocalId("draft-rr");
    draft.setDraftState(DraftState.LOCAL_ONLY);
    draft.setDraftRevision(revision);
    draft.setDraftUpdatedDate(now);
    draft.setReadReceiptRequested(requested);
    return draft;
  }
}
