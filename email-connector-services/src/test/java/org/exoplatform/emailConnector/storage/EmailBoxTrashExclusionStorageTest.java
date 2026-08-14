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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Map;

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
import org.exoplatform.emailConnector.model.ThreadSummary;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * What a cached {@link MailFolder#TRASH} row is and is not allowed to reach.
 * <p>
 * Nothing writes a TRASH row yet, which is exactly why these tests exist and why
 * they are the only place the change can be observed at all: the moment the Trash
 * folder starts being cached, every cross-folder read that carries no folder
 * predicate begins answering with deleted mail — a message thrown out of a
 * conversation reappearing inside it, a search offering back what the user threw
 * away — silently, and everywhere at once. So the rows are written here by hand,
 * ahead of the sync that will write them for real, and the reads are asked what they
 * answer.
 * <p>
 * At the persistence level, on the SHIPPED Liquibase changelog and with the ambient
 * transaction taken away ({@link Propagation#NOT_SUPPORTED}, the rig
 * {@link EmailBoxDraftStorageTest} documents), because the whole of what is under
 * test is a set of SQL predicates. A mocked DAO returning a hand-built list would
 * assert that the mapper still reads its columns and nothing whatsoever about the
 * queries that are the change.
 * <p>
 * The two totality cases are here for the same reason as the six exclusions and are
 * not their opposite: getting the direction wrong on the mailbox wipe leaks rows that
 * outlive the account they belonged to, which is a worse failure than the one the
 * exclusions prevent and has no screen anywhere to reveal it.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailBoxStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailBoxTrashExclusionStorageTest {

  // One mailbox per test: nothing rolls back here, so a cross-folder read would
  // otherwise pick up the rows of every earlier test.
  private static final String READER_USER       = "alice";

  private static final String COUNT_USER        = "bob";

  private static final String DISCARDED_USER    = "carol";

  private static final String PARTICIPANTS_USER = "dave";

  private static final String SEARCH_USER       = "erin";

  private static final String WIPE_USER         = "frank";

  private static final String COUNTS_USER       = "grace";

  private static final String RESUME_USER       = "heidi";

  private static final String REWRITE_USER      = "ivan";

  private static final String BADGE_USER        = "judy";

  private static final String THREAD_ID         = "<monday@example.org>";

  private static final long   MONDAY            = 1_000_000_000_000L;

  private static final long   TUESDAY           = MONDAY + 86_400_000L;

  private static final long   WEDNESDAY         = MONDAY + 2 * 86_400_000L;

  @Autowired
  private EmailBoxStorage     emailBoxStorage;

  @MockBean
  private CategoryLinkService categoryLinkService;

  // Mocked rather than exercised: nothing here attaches a file, and an unmocked bean
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
   * The case the whole slice exists for: a message deleted out of a conversation
   * stays out of it. The conversation reader is the read where the failure is most
   * visible — the user deleted this message from this very conversation, and a reader
   * with no folder predicate puts it straight back the next time they open it.
   * <p>
   * The other folders are untouched, which is the point of a cross-folder reader:
   * the sent reply still reads inline.
   */
  @Test
  void aMessageDeletedOutOfAConversationDoesNotComeBackIntoIt() {
    emailBoxStorage.createEmail(mail(READER_USER, MailFolder.INBOX, 1L, "<monday@example.org>", MONDAY));
    emailBoxStorage.createEmail(mail(READER_USER, MailFolder.SENT, 2L, "<tuesday@example.org>", TUESDAY));
    emailBoxStorage.createEmail(mail(READER_USER, MailFolder.TRASH, 3L, "<deleted@example.org>", WEDNESDAY));

    List<Email> conversation = emailBoxStorage.getEmailsByThreadId(READER_USER, THREAD_ID, "alice@example.org");

    assertEquals(List.of("<monday@example.org>", "<tuesday@example.org>"),
                 conversation.stream().map(Email::getMailHeaderId).toList(),
                 "a message the user deleted must not reappear in the conversation they deleted it from");
  }

  /**
   * The number beside the participants counts what the reader will show. A
   * conversation whose third message was deleted holds two, and the count is the one
   * place that could go on insisting on three with nothing on screen to account for
   * the difference.
   */
  @Test
  void aDeletedMessageIsNotCountedInItsConversation() {
    emailBoxStorage.createEmail(mail(COUNT_USER, MailFolder.INBOX, 1L, "<monday@example.org>", MONDAY));
    emailBoxStorage.createEmail(mail(COUNT_USER, MailFolder.SENT, 2L, "<tuesday@example.org>", TUESDAY));
    emailBoxStorage.createEmail(mail(COUNT_USER, MailFolder.TRASH, 3L, "<deleted@example.org>", WEDNESDAY));

    ThreadSummary summary = summaryOf(COUNT_USER, "bob@example.org");

    assertEquals(2, summary.messageCount(), "the count is the messages the conversation still shows, not the ones it held");
  }

  /**
   * A draft the user discarded is a TRASH row that still carries a draft local id.
   * Left in the summary it would keep the conversation advertising unfinished words
   * that no longer exist — the row saying "Draft" and the reader, which excludes the
   * same folder, having nothing to open.
   */
  @Test
  void aDiscardedDraftStopsItsConversationClaimingOne() {
    emailBoxStorage.createEmail(mail(DISCARDED_USER, MailFolder.INBOX, 1L, "<monday@example.org>", MONDAY));
    emailBoxStorage.createEmail(discardedDraft(DISCARDED_USER, "draft-1", TUESDAY));

    ThreadSummary summary = summaryOf(DISCARDED_USER, "carol@example.org");

    assertFalse(summary.hasDraft(), "a draft the user threw away is not a reply they have yet to send");
    assertTrue(summary.participants().isEmpty(),
               "and with no draft left to label, there is nobody to name beside it");
  }

  /**
   * The participants of a draft-carrying conversation are the people whose messages
   * the reader will show. Somebody whose only message in it was deleted is not one of
   * them any more — the names have to agree with the mail.
   */
  @Test
  void aDeletedSenderIsNotNamedAmongAConversationsParticipants() {
    emailBoxStorage.createEmail(from(mail(PARTICIPANTS_USER, MailFolder.INBOX, 1L, "<monday@example.org>", MONDAY),
                                     new EmailSender("Veronika", "veronika@example.org", null, null)));
    emailBoxStorage.createEmail(from(mail(PARTICIPANTS_USER, MailFolder.TRASH, 2L, "<deleted@example.org>", TUESDAY),
                                     new EmailSender("Gianni", "gianni@example.org", null, null)));
    emailBoxStorage.createEmail(liveDraft(PARTICIPANTS_USER, "draft-1", WEDNESDAY));

    ThreadSummary summary = summaryOf(PARTICIPANTS_USER, "dave@example.org");

    assertTrue(summary.hasDraft(), "the live draft is still a reply the user has yet to send");
    assertEquals(List.of("Veronika"),
                 summary.participants(),
                 "the conversation is with the people it still shows, not with the one whose message was deleted");
  }

  /**
   * Search is the read where a deleted message is hardest to spot as one: a hit is a
   * subject, a sender and a date, and says nothing about the folder it came from. So
   * it is also the quietest way to hand somebody back what they threw away.
   */
  @Test
  void aDeletedMessageIsNotOfferedBackBySearch() {
    emailBoxStorage.createEmail(mail(SEARCH_USER, MailFolder.INBOX, 1L, "<monday@example.org>", MONDAY));
    emailBoxStorage.createEmail(mail(SEARCH_USER, MailFolder.TRASH, 2L, "<deleted@example.org>", TUESDAY));

    assertEquals(List.of("<monday@example.org>"),
                 emailBoxStorage.getEmailsExcludingTrash(SEARCH_USER).stream().map(Email::getMailHeaderId).toList(),
                 "what the user threw away must not be searchable back out of the bin");
  }

  /**
   * The other direction, and the one whose failure has no screen to reveal it:
   * disconnecting or rebinding a mailbox wipes what it read, and the wipe reads THIS
   * query to know what to delete. A TRASH row left out of it is deleted mail that
   * outlives the account it was deleted in.
   */
  @Test
  void wipingAMailboxStillReachesTheMailTheUserDeleted() {
    emailBoxStorage.createEmail(mail(WIPE_USER, MailFolder.INBOX, 1L, "<monday@example.org>", MONDAY));
    emailBoxStorage.createEmail(mail(WIPE_USER, MailFolder.TRASH, 2L, "<deleted@example.org>", TUESDAY));

    List<Email> everything = emailBoxStorage.getEmails(WIPE_USER);

    assertEquals(2, everything.size(), "the wipe has to see every row the mailbox produced, the deleted ones included");
    assertTrue(everything.stream().anyMatch(email -> MailFolder.TRASH.equals(email.getFolder())),
               "including the folder no other read is allowed to answer with");

    emailBoxStorage.deleteEmailsByIds(everything.stream().map(Email::getId).toList());

    assertTrue(emailBoxStorage.getEmails(WIPE_USER).isEmpty(), "and nothing of the mailbox may survive the disconnect");
  }

  /**
   * The one place a TRASH row is SUPPOSED to be seen outside a Trash listing: the
   * per-folder counts the list's folder switch is built from. This is the
   * "browsable" half of the rule — a user who opens Trash asked to see what they
   * threw away — and the counts are what will offer them the way in.
   */
  @Test
  void theFolderCountsStillReportTheDeletedMail() {
    emailBoxStorage.createEmail(mail(COUNTS_USER, MailFolder.INBOX, 1L, "<monday@example.org>", MONDAY));
    emailBoxStorage.createEmail(mail(COUNTS_USER, MailFolder.TRASH, 2L, "<deleted@example.org>", TUESDAY));

    Map<String, Integer> counts = emailBoxStorage.getFolderMessageCounts(COUNTS_USER);

    assertEquals(1, counts.get(MailFolder.INBOX));
    assertEquals(1, counts.get(MailFolder.TRASH), "a Trash tab with something in it has to be offered, not hidden");
  }

  /**
   * A draft the user discarded keeps its local id, and the local id is how the
   * composer reopens a draft — so the read behind that has to stop answering with it.
   * <p>
   * Reachable only now that a row can be in TRASH at all, which is why it is fixed in
   * the slice that makes it reachable rather than the one that will produce the row.
   * Unscoped, the composer would resume a draft the user threw away, an autosave would
   * write text into the bin, and a send would transmit from a row nothing shows.
   */
  @Test
  void aDiscardedDraftIsNotResumableByItsLocalId() {
    emailBoxStorage.createEmail(discardedDraft(RESUME_USER, "draft-1", TUESDAY));

    assertNull(emailBoxStorage.getDraftByLocalId(RESUME_USER, "draft-1"),
               "a draft in the bin is not a draft the composer may reopen");
  }

  /**
   * The other half of the same fix, and the destructive one: a save addressed to a
   * discarded draft's id must not land ON the trashed row. It writes a new draft
   * instead — which is what the composer's own handle means once the old one is gone
   * — and leaves what is in the bin exactly as the user left it.
   */
  @Test
  void savingAgainstADiscardedDraftDoesNotWriteIntoTheBin() {
    emailBoxStorage.createEmail(discardedDraft(REWRITE_USER, "draft-1", TUESDAY));

    Email rewritten = draftRow(REWRITE_USER, MailFolder.DRAFTS, "draft-1", WEDNESDAY);
    rewritten.setContent(new EmailContent("<p>a second attempt</p>", null, null));
    rewritten.setDraftRevision(2L);
    emailBoxStorage.saveDraft(rewritten);

    List<Email> rows = emailBoxStorage.getEmails(REWRITE_USER);
    Email trashed = rows.stream().filter(email -> MailFolder.TRASH.equals(email.getFolder())).findFirst().orElse(null);
    assertTrue(trashed != null, "the discarded draft is still in the bin");
    assertEquals("<p>half an answer</p>",
                 trashed.getContent().getBody(),
                 "and still holds what the user discarded, not what they typed afterwards");
    assertEquals("<p>a second attempt</p>",
                 emailBoxStorage.getDraftByLocalId(REWRITE_USER, "draft-1").getContent().getBody(),
                 "while the new words are a draft of their own");
  }

  /**
   * The exclusion a user meets without ever opening a Trash listing. Deleting an
   * unread mail is how most people dismiss one, the message keeps its unread flag all
   * the way into the server's Trash, and a badge counting the whole mailbox would take
   * that count straight back at the next sync — insisting on unread mail with nothing
   * on any screen to account for it.
   */
  @Test
  void deletingAnUnreadMessageTakesItOutOfTheBadge() {
    emailBoxStorage.createEmail(unread(mail(BADGE_USER, MailFolder.INBOX, 1L, "<monday@example.org>", MONDAY)));
    emailBoxStorage.createEmail(unread(mail(BADGE_USER, MailFolder.TRASH, 2L, "<deleted@example.org>", TUESDAY)));

    assertEquals(1, emailBoxStorage.countUnreadEmails(BADGE_USER), "deleting an unread message is a way of reading it");
  }

  /**
   * Marks a message unread, as it arrives and as it stays on the way to the bin.
   *
   * @param email the message
   * @return the same message
   */
  private Email unread(Email email) {
    email.setRead(false);
    return email;
  }

  /**
   * The summary of the one conversation these tests use.
   *
   * @param userId the mailbox owner
   * @param userEmail the owner's own address, kept out of the participant names
   * @return the conversation's summary
   */
  private ThreadSummary summaryOf(String userId, String userEmail) {
    Map<String, ThreadSummary> summaries = emailBoxStorage.getThreadSummaries(userId, userEmail);
    ThreadSummary summary = summaries.get(THREAD_ID);
    assertTrue(summary != null, "no summary was returned for " + THREAD_ID);
    return summary;
  }

  /**
   * Puts a message under a given sender.
   *
   * @param email the message
   * @param sender who wrote it
   * @return the same message
   */
  private Email from(Email email, EmailSender sender) {
    email.setSender(sender);
    return email;
  }

  /**
   * A cached message of the conversation, in whichever folder it is cached.
   *
   * @param userId the mailbox owner
   * @param folder the {@link MailFolder} discriminator
   * @param mailRemoteId the IMAP UID within that folder
   * @param messageId its Message-ID
   * @param receivedAt when it was delivered
   * @return the message to write
   */
  private Email mail(String userId, String folder, long mailRemoteId, String messageId, long receivedAt) {
    Email email = new Email();
    email.setUserId(userId);
    email.setFolder(folder);
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
   * A draft the user is still writing, filed in DRAFTS as the composer saves it.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @param writtenAt when the user last typed in it
   * @return the draft to write
   */
  private Email liveDraft(String userId, String draftLocalId, long writtenAt) {
    return draftRow(userId, MailFolder.DRAFTS, draftLocalId, writtenAt);
  }

  /**
   * The same draft after the user discarded it: the row is in TRASH and still
   * carries the local id everything about a draft is addressed by, which is what
   * makes it invisible to a folder predicate and visible to everything without one.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @param writtenAt when the user last typed in it
   * @return the discarded draft to write
   */
  private Email discardedDraft(String userId, String draftLocalId, long writtenAt) {
    return draftRow(userId, MailFolder.TRASH, draftLocalId, writtenAt);
  }

  /**
   * A draft row of the conversation, in a given folder.
   *
   * @param userId the mailbox owner
   * @param folder the {@link MailFolder} discriminator
   * @param draftLocalId the composer's handle on the draft
   * @param writtenAt when the user last typed in it
   * @return the row to write
   */
  private Email draftRow(String userId, String folder, String draftLocalId, long writtenAt) {
    Date now = new Date(writtenAt);
    Email draft = new Email();
    draft.setUserId(userId);
    draft.setFolder(folder);
    draft.setMailHeaderId("<" + draftLocalId + "@example.org>");
    draft.setThreadId(THREAD_ID);
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
