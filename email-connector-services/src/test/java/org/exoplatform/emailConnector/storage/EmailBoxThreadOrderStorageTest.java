/**
 * Copyright (C) 2025 eXo Platform SAS
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

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * The order the messages of one conversation are read in, and where a draft sits in
 * it.
 * <p>
 * A draft used to land wherever its date put it, and its date is rewritten on every
 * save — so resuming a reply to Monday's message at nine tonight moved it below a
 * message that arrived at 20:57 and answered nothing at all. The conversation then
 * reads as if the user were replying to the newest message. Position belongs to what
 * the draft ANSWERS, which its In-Reply-To already says.
 * <p>
 * Written at the persistence level rather than against a mocked DAO, and on the
 * SHIPPED Liquibase changelog with the ambient transaction taken away
 * ({@link Propagation#NOT_SUPPORTED}, the rig
 * {@link EmailBoxDraftStorageTest} documents), because what is being asserted is the
 * whole read: the query's own date ordering, the mapping, and the repositioning on
 * top of them. A stub returning a hand-built list would assert the last of the three
 * and none of the two it rests on.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailBoxStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailBoxThreadOrderStorageTest {

  // One mailbox per test: nothing rolls back here, so a conversation read would
  // otherwise pick up the rows of every earlier test.
  private static final String ANSWERS_OLDEST_USER  = "alice";

  private static final String ANSWERS_NEWEST_USER  = "bob";

  private static final String PLAIN_DRAFT_USER     = "carol";

  private static final String ABSENT_PARENT_USER   = "dave";

  private static final String THREAD_ID            = "<monday@example.org>";

  private static final long   MONDAY               = 1_000_000_000_000L;

  private static final long   TUESDAY              = MONDAY + 86_400_000L;

  private static final long   TONIGHT              = MONDAY + 4 * 86_400_000L;

  // Later than every message of the conversation, which is what a draft's date is
  // after the user has just typed in it — and what used to put it last.
  private static final long   AFTER_TONIGHT        = TONIGHT + 3_600_000L;

  @Autowired
  private EmailBoxStorage     emailBoxStorage;

  @MockBean
  private CategoryLinkService categoryLinkService;

  // The storage now writes attachment bytes through the platform's file service. Mocked
  // rather than exercised: nothing in this class attaches a file, and an unmocked bean
  // would simply fail to start the context.
  @MockBean
  private FileService         fileService;

  @MockBean
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
   * The reported case: three messages and a draft answering the FIRST of them. The
   * draft reads second, directly under the message it answers, though it was written
   * after all three.
   */
  @Test
  void aDraftAnsweringTheOldestMessageReadsSecond() {
    emailBoxStorage.createEmail(mail(ANSWERS_OLDEST_USER, 1L, "<monday@example.org>", MONDAY));
    emailBoxStorage.createEmail(mail(ANSWERS_OLDEST_USER, 2L, "<tuesday@example.org>", TUESDAY));
    emailBoxStorage.createEmail(mail(ANSWERS_OLDEST_USER, 3L, "<tonight@example.org>", TONIGHT));
    emailBoxStorage.saveDraft(draft(ANSWERS_OLDEST_USER, "draft-1", "<monday@example.org>", AFTER_TONIGHT));

    List<Email> conversation = emailBoxStorage.getEmailsByThreadId(ANSWERS_OLDEST_USER, THREAD_ID, "alice@example.org");

    assertEquals(List.of("<monday@example.org>", "draft-1", "<tuesday@example.org>", "<tonight@example.org>"),
                 conversation.stream().map(EmailBoxThreadOrderStorageTest::rowKey).toList(),
                 "a draft belongs directly under the message it answers, not at the end of the conversation");
  }

  /**
   * A draft answering the NEWEST message still reads last — the common case, and the
   * one the previous behaviour got right by accident. It must keep working, since
   * this is what most replies are.
   */
  @Test
  void aDraftAnsweringTheNewestMessageStillReadsLast() {
    emailBoxStorage.createEmail(mail(ANSWERS_NEWEST_USER, 1L, "<monday@example.org>", MONDAY));
    emailBoxStorage.createEmail(mail(ANSWERS_NEWEST_USER, 2L, "<tuesday@example.org>", TUESDAY));
    emailBoxStorage.createEmail(mail(ANSWERS_NEWEST_USER, 3L, "<tonight@example.org>", TONIGHT));
    emailBoxStorage.saveDraft(draft(ANSWERS_NEWEST_USER, "draft-1", "<tonight@example.org>", AFTER_TONIGHT));

    List<Email> conversation = emailBoxStorage.getEmailsByThreadId(ANSWERS_NEWEST_USER, THREAD_ID, "bob@example.org");

    assertEquals(List.of("<monday@example.org>", "<tuesday@example.org>", "<tonight@example.org>", "draft-1"),
                 conversation.stream().map(EmailBoxThreadOrderStorageTest::rowKey).toList(),
                 "answering the last message means reading last, which is where most replies belong");
  }

  /**
   * A draft that answers nothing — a message being written from scratch — reads last.
   * It has no parent to sit under, and the end of the conversation is where an
   * unanswered draft belongs.
   */
  @Test
  void aDraftThatAnswersNothingReadsLast() {
    emailBoxStorage.createEmail(mail(PLAIN_DRAFT_USER, 1L, "<monday@example.org>", MONDAY));
    emailBoxStorage.createEmail(mail(PLAIN_DRAFT_USER, 2L, "<tonight@example.org>", TONIGHT));
    emailBoxStorage.saveDraft(draft(PLAIN_DRAFT_USER, "draft-1", null, AFTER_TONIGHT));

    List<Email> conversation = emailBoxStorage.getEmailsByThreadId(PLAIN_DRAFT_USER, THREAD_ID, "carol@example.org");

    assertEquals(List.of("<monday@example.org>", "<tonight@example.org>", "draft-1"),
                 conversation.stream().map(EmailBoxThreadOrderStorageTest::rowKey).toList(),
                 "with nothing to answer there is nothing to sit under");
  }

  /**
   * A draft whose parent is NOT cached reads last too. The message it answers can
   * have fallen out of the cache (the trim keeps a window, and an old thread reopened
   * from the archive is completed piecemeal), and a draft must not be dropped, hidden
   * or thrown to an arbitrary place because the row it points at is missing.
   */
  @Test
  void aDraftWhoseParentIsNotCachedReadsLast() {
    emailBoxStorage.createEmail(mail(ABSENT_PARENT_USER, 1L, "<monday@example.org>", MONDAY));
    emailBoxStorage.createEmail(mail(ABSENT_PARENT_USER, 2L, "<tonight@example.org>", TONIGHT));
    emailBoxStorage.saveDraft(draft(ABSENT_PARENT_USER, "draft-1", "<evicted-long-ago@example.org>", AFTER_TONIGHT));

    List<Email> conversation = emailBoxStorage.getEmailsByThreadId(ABSENT_PARENT_USER, THREAD_ID, "dave@example.org");

    assertEquals(List.of("<monday@example.org>", "<tonight@example.org>", "draft-1"),
                 conversation.stream().map(EmailBoxThreadOrderStorageTest::rowKey).toList(),
                 "a parent that is not here is the same as no parent: the draft goes to the end");
  }

  /**
   * How a row of the conversation is named in an assertion: its Message-ID for mail,
   * its local id for a draft — the same distinction every other layer keys them by.
   *
   * @param message a message of the conversation
   * @return the key to compare positions on
   */
  private static String rowKey(Email message) {
    return message.getDraftLocalId() != null ? message.getDraftLocalId() : message.getMailHeaderId();
  }

  /**
   * A cached message of the conversation.
   *
   * @param userId the mailbox owner
   * @param mailRemoteId the IMAP UID
   * @param messageId its Message-ID
   * @param receivedAt when it was delivered
   * @return the message to write
   */
  private Email mail(String userId, long mailRemoteId, String messageId, long receivedAt) {
    Email email = new Email();
    email.setUserId(userId);
    email.setFolder(MailFolder.INBOX);
    email.setMailRemoteId(mailRemoteId);
    email.setMailHeaderId(messageId);
    email.setThreadId(THREAD_ID);
    email.setSender(new EmailSender("Veronika", "veronika@example.org", null, null));
    email.setTo(List.of(new EmailRecipient("Alice", "alice@example.org", null, false)));
    email.setSubject("A conversation");
    email.setContent(new EmailContent("<p>a message</p>", null, null));
    email.setReceivedDate(new Date(receivedAt));
    return email;
  }

  /**
   * A draft of the same conversation, as the composer saves it.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @param inReplyTo the Message-ID of the message it answers, null for a draft that
   *          answers nothing
   * @param writtenAt when the user last typed in it
   * @return the draft to write
   */
  private Email draft(String userId, String draftLocalId, String inReplyTo, long writtenAt) {
    Date now = new Date(writtenAt);
    Email draft = new Email();
    draft.setUserId(userId);
    draft.setFolder(MailFolder.DRAFTS);
    draft.setMailHeaderId("<" + draftLocalId + "@example.org>");
    draft.setThreadId(THREAD_ID);
    draft.setInReplyTo(inReplyTo);
    draft.setSender(new EmailSender("Alice", "alice@example.org", null, null));
    draft.setTo(List.of(new EmailRecipient("Veronika", "veronika@example.org", null, false)));
    draft.setSubject("Re: A conversation");
    draft.setContent(new EmailContent("<p>half an answer</p>", null, null));
    draft.setReceivedDate(now);
    draft.setRead(true);
    draft.setDraftLocalId(draftLocalId);
    draft.setDraftState(DraftState.LOCAL_ONLY);
    draft.setDraftRevision(1L);
    draft.setDraftUpdatedDate(now);
    return draft;
  }
}
