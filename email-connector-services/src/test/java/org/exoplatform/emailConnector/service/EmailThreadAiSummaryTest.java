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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ThreadAiSummary;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.scheduler.JobSchedulerService;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;
import io.meeds.social.category.service.CategoryService;
import lombok.SneakyThrows;

/**
 * The conversation-summary cache, from the mail rows up: real messages in a real
 * database, the real fingerprint query over them, and the real verdict on whether a
 * stored summary still describes the conversation.
 * <p>
 * Written this way rather than over a mocked storage because the rule under test is
 * not a comparison of two numbers — it is a comparison of two numbers ARRIVED AT by a
 * query, and every interesting case here is a case about what that query counts. A
 * test that stubbed the fingerprint would be asserting the arithmetic in
 * {@code isStale} and nothing about drafts, folders or duplicates, which is where all
 * three of the deliberate consequences live. The rig is the one
 * {@code EmailBoxThreadOrderStorageTest} documents: the shipped Liquibase changelog,
 * {@code ddl-auto=none}, and the ambient transaction taken away so the bulk deletes
 * are visible to the reads that follow.
 * <p>
 * A mailbox per test, since nothing rolls back.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import({ EmailBoxStorage.class, EmailBoxService.class })
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailThreadAiSummaryTest {

  private static final String     THREAD_ID    = "<monday@example.org>";

  private static final String     AGENT        = "SUMMARIZE_THREAD";

  private static final long       MONDAY       = 1_000_000_000_000L;

  private static final long       TUESDAY      = MONDAY + 86_400_000L;

  private static final long       WEDNESDAY    = MONDAY + 2 * 86_400_000L;

  @Autowired
  private EmailBoxService         emailBoxService;

  @Autowired
  private EmailBoxStorage         emailBoxStorage;

  @MockitoBean
  private UserEmailSettingService userEmailSettingService;

  @MockitoBean
  private SettingService          settingService;

  @MockitoBean
  private JobSchedulerService     jobSchedulerService;

  @MockitoBean
  private ListenerService         listenerService;

  @MockitoBean
  private EmailConnectorService   emailConnectorService;

  @MockitoBean
  private CategoryLinkService     categoryLinkService;

  @MockitoBean
  private CategoryService         categoryService;

  @MockitoBean
  private EmailFavoriteService    emailFavoriteService;

  @MockitoBean
  private EmailSignatureService   emailSignatureService;

  @MockitoBean
  private ApplicationEventPublisher eventPublisher;

  @MockitoBean
  private FileService             fileService;

  @MockitoBean
  private UploadService           uploadService;

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
   * Every test here reads or writes a summary, and every one of those goes through the
   * mailbox guard first, so the mailbox is bound and connectable throughout. The one
   * test that is ABOUT the guard says so by taking it away again.
   */
  @BeforeEach
  void bindMailbox() {
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailConnectorId("1");
    setting.setEmailAddress("alice@example.org");
    when(userEmailSettingService.getUserEmailSetting(anyString())).thenReturn(setting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
  }

  /**
   * A conversation nobody has summarised answers nothing at all, rather than an empty
   * summary. This is what every deployment without a producer installed looks like,
   * for every conversation, forever — so it is the normal case and not the edge one.
   */
  @Test
  @SneakyThrows
  void aConversationWithNoSummaryHasNone() {
    String user = "nosummary";
    emailBoxStorage.createEmail(mail(user, 1L, "<monday@example.org>", MONDAY));

    assertNull(emailBoxService.getThreadAiSummary(THREAD_ID, user), "nothing has been written about this conversation");
  }

  /**
   * The plain case: a summary written of the conversation as it stands still describes
   * it.
   */
  @Test
  @SneakyThrows
  void aSummaryOfTheConversationAsItStandsIsNotStale() {
    String user = "fresh";
    emailBoxStorage.createEmail(mail(user, 1L, "<monday@example.org>", MONDAY));
    emailBoxStorage.createEmail(mail(user, 2L, "<tuesday@example.org>", TUESDAY));
    emailBoxService.saveThreadAiSummary(THREAD_ID, "They agreed on Thursday.", AGENT, user);

    ThreadAiSummary summary = emailBoxService.getThreadAiSummary(THREAD_ID, user);

    assertNotNull(summary);
    assertEquals("They agreed on Thursday.", summary.summary());
    assertFalse(summary.stale(), "nothing has happened to this conversation since it was summarised");
    assertNotNull(summary.generatedDate(), "a reader is shown when the words were written");
  }

  /**
   * The case the whole fingerprint exists for: a message arrives, and the summary stops
   * claiming to describe the conversation.
   */
  @Test
  @SneakyThrows
  void aNewMessageMakesTheSummaryStale() {
    String user = "newmail";
    emailBoxStorage.createEmail(mail(user, 1L, "<monday@example.org>", MONDAY));
    emailBoxService.saveThreadAiSummary(THREAD_ID, "Véronika asked about the contract.", AGENT, user);

    emailBoxStorage.createEmail(mail(user, 2L, "<tuesday@example.org>", TUESDAY));

    assertTrue(emailBoxService.getThreadAiSummary(THREAD_ID, user).stale(),
               "the conversation has gained a message the summary cannot know about");
  }

  /**
   * A message that lands with an OLDER date than the newest one already cached — a
   * delayed delivery, or the archived tail recovered when the conversation was opened.
   * <p>
   * The newest message is unchanged, so the key half of the fingerprint says nothing
   * has happened; the count is what catches it. This is why the rule is two comparisons
   * and not one, and it is the case that would silently pass if it were only the key.
   */
  @Test
  @SneakyThrows
  void aMessageRecoveredIntoTheMiddleMakesTheSummaryStale() {
    String user = "recovered";
    emailBoxStorage.createEmail(mail(user, 1L, "<monday@example.org>", MONDAY));
    emailBoxStorage.createEmail(mail(user, 2L, "<wednesday@example.org>", WEDNESDAY));
    emailBoxService.saveThreadAiSummary(THREAD_ID, "Two messages.", AGENT, user);

    emailBoxStorage.createEmail(mail(user, 3L, "<tuesday@example.org>", TUESDAY));

    assertTrue(emailBoxService.getThreadAiSummary(THREAD_ID, user).stale(),
               "an older message is still a message the summary was written without");
  }

  /**
   * The consequence that reads backwards and is deliberate: the local cache trimming
   * the conversation's oldest messages does NOT make the summary stale.
   * <p>
   * The count has fallen rather than risen, which means the summary was written from
   * MORE of the conversation than is left on this machine — it is the better answer of
   * the two, not the out-of-date one. A rule written with "different from" instead of
   * "greater than" would throw it away and ask for a poorer one in its place, every
   * time the sync window moved.
   */
  @Test
  @SneakyThrows
  void trimmingTheOldestMessagesDoesNotMakeTheSummaryStale() {
    String user = "trimmed";
    Email oldest = emailBoxStorage.createEmail(mail(user, 1L, "<monday@example.org>", MONDAY));
    emailBoxStorage.createEmail(mail(user, 2L, "<tuesday@example.org>", TUESDAY));
    emailBoxService.saveThreadAiSummary(THREAD_ID, "Two messages, and this describes both.", AGENT, user);

    // What cleanupObsoleteEmails does when the conversation's oldest message falls out
    // of the cache window: the row goes, and nothing replaces it.
    emailBoxStorage.deleteEmailsByIds(List.of(oldest.getId()));

    assertFalse(emailBoxService.getThreadAiSummary(THREAD_ID, user).stale(),
                "a summary written from more of the conversation is not out of date because less of it is left");
  }

  /**
   * A draft is not a message. The user starting a reply — and then typing in it, which
   * re-dates its row every time — leaves the summary alone.
   * <p>
   * The draft here is dated LATER than every message of the conversation, which is what
   * a draft's date always is a moment after somebody has typed in it, and is exactly
   * what would take over the "newest message" half of the fingerprint if drafts were
   * counted. It is also the case that matters most in practice: the alternative is a
   * summary that goes stale between two words, and a re-summarisation whose subject is
   * the half-finished sentence the user is still writing.
   */
  @Test
  @SneakyThrows
  void aDraftDoesNotMakeTheSummaryStale() {
    String user = "drafting";
    emailBoxStorage.createEmail(mail(user, 1L, "<monday@example.org>", MONDAY));
    emailBoxService.saveThreadAiSummary(THREAD_ID, "Véronika asked about the contract.", AGENT, user);

    emailBoxStorage.saveDraft(draft(user, "draft-1", WEDNESDAY));

    assertFalse(emailBoxService.getThreadAiSummary(THREAD_ID, user).stale(),
                "an unsent reply is not part of the conversation anybody means to have described");
  }

  /**
   * The same message cached in two folders is one message, not a conversation that has
   * grown — the rule the count already followed everywhere else, applied here because
   * a provider whose All Mail overlaps the inbox makes this the normal case.
   */
  @Test
  @SneakyThrows
  void theSameMessageCachedTwiceIsNotANewMessage() {
    String user = "duplicated";
    emailBoxStorage.createEmail(mail(user, 1L, "<monday@example.org>", MONDAY));
    emailBoxStorage.createEmail(mail(user, 2L, "<tuesday@example.org>", TUESDAY));
    emailBoxService.saveThreadAiSummary(THREAD_ID, "Two messages.", AGENT, user);

    // The archive copy of a message the inbox already holds: same Message-ID, its own
    // folder and its own UID, and its own received date unchanged.
    Email archived = mail(user, 90L, "<monday@example.org>", MONDAY);
    archived.setFolder(MailFolder.ALL_MAIL);
    emailBoxStorage.createEmail(archived);

    assertFalse(emailBoxService.getThreadAiSummary(THREAD_ID, user).stale(),
                "one message filed in two folders is still one message");
  }

  /**
   * Writing a summary twice leaves one summary, not two — it is a cache entry, and the
   * previous answer is of no interest once a newer one exists.
   */
  @Test
  @SneakyThrows
  void writingASummaryAgainReplacesIt() {
    String user = "rewritten";
    emailBoxStorage.createEmail(mail(user, 1L, "<monday@example.org>", MONDAY));
    emailBoxService.saveThreadAiSummary(THREAD_ID, "A first attempt.", AGENT, user);
    emailBoxService.saveThreadAiSummary(THREAD_ID, "A better one.", AGENT, user);

    assertEquals("A better one.", emailBoxService.getThreadAiSummary(THREAD_ID, user).summary());
  }

  /**
   * Saving one takes the fingerprint of the conversation as it is NOW, so the summary
   * comes back fresh rather than inheriting the staleness of whatever it replaced.
   */
  @Test
  @SneakyThrows
  void writingASummaryAgainMakesItFreshAgain() {
    String user = "refreshed";
    emailBoxStorage.createEmail(mail(user, 1L, "<monday@example.org>", MONDAY));
    emailBoxService.saveThreadAiSummary(THREAD_ID, "One message.", AGENT, user);
    emailBoxStorage.createEmail(mail(user, 2L, "<tuesday@example.org>", TUESDAY));
    assertTrue(emailBoxService.getThreadAiSummary(THREAD_ID, user).stale());

    emailBoxService.saveThreadAiSummary(THREAD_ID, "Two messages now.", AGENT, user);

    assertFalse(emailBoxService.getThreadAiSummary(THREAD_ID, user).stale(), "it was just written of what is there");
  }

  /**
   * A conversation with no message of this user's in it cannot be summarised into their
   * mailbox — a thread id is a string somebody can guess, and this is the only thing
   * standing between a guessed one and a row written under another person's name.
   */
  @Test
  @SneakyThrows
  void aSummaryCannotBeWrittenAboutSomebodyElsesConversation() {
    String owner = "owner";
    String stranger = "stranger";
    emailBoxStorage.createEmail(mail(owner, 1L, "<monday@example.org>", MONDAY));

    assertThrows(IllegalAccessException.class,
                 () -> emailBoxService.saveThreadAiSummary(THREAD_ID, "Not yours.", AGENT, stranger),
                 "a conversation the user has no message in is not theirs to have summarised");
  }

  /**
   * One user's summary is not another's, even of a conversation whose id they share —
   * which two people on one mailing list legitimately do, since the id is minted from
   * Message-IDs they both received.
   */
  @Test
  @SneakyThrows
  void oneUsersSummaryIsNotAnothers() {
    String first = "veronika";
    String second = "gianni";
    emailBoxStorage.createEmail(mail(first, 1L, "<monday@example.org>", MONDAY));
    emailBoxStorage.createEmail(mail(second, 1L, "<monday@example.org>", MONDAY));
    emailBoxService.saveThreadAiSummary(THREAD_ID, "Véronika's copy.", AGENT, first);

    assertEquals("Véronika's copy.", emailBoxService.getThreadAiSummary(THREAD_ID, first).summary());
    assertNull(emailBoxService.getThreadAiSummary(THREAD_ID, second),
               "the same conversation id in another mailbox is another conversation");
  }

  /**
   * A mailbox that may not be read does not answer summaries about it either. The same
   * guard as the conversation read itself, which is the point: a summary IS the
   * conversation, in fewer words.
   */
  @Test
  @SneakyThrows
  void anUnreadableMailboxAnswersNothing() {
    String user = "revoked";
    emailBoxStorage.createEmail(mail(user, 1L, "<monday@example.org>", MONDAY));
    emailBoxService.saveThreadAiSummary(THREAD_ID, "Written while it was allowed.", AGENT, user);

    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);

    assertThrows(IllegalAccessException.class, () -> emailBoxService.getThreadAiSummary(THREAD_ID, user));
    assertThrows(IllegalAccessException.class, () -> emailBoxService.requestThreadAiSummary(THREAD_ID, user));
    assertThrows(IllegalAccessException.class, () -> emailBoxService.saveThreadAiSummary(THREAD_ID, "…", AGENT, user));
  }

  /**
   * Asking for a summary broadcasts a request and nothing else. There is no producer in
   * this add-on, and this test is what says so: whatever writes summaries listens for
   * this and calls back.
   */
  @Test
  @SneakyThrows
  void askingForASummaryBroadcastsARequest() {
    String user = "asking";
    emailBoxStorage.createEmail(mail(user, 1L, "<monday@example.org>", MONDAY));

    emailBoxService.requestThreadAiSummary(THREAD_ID, user);

    verify(listenerService).broadcast(EmailConnectorUtils.THREAD_AI_SUMMARY_REQUESTED, user, THREAD_ID);
    assertNull(emailBoxService.getThreadAiSummary(THREAD_ID, user), "asking is not writing: nothing here can produce one");
  }

  /**
   * Two conversations found to be one: the summary of the id that disappears goes with
   * it.
   * <p>
   * Left behind it would be filed under an id no reader will ever ask for — and would
   * come back to life describing a fragment if a later merge ever ran the other way,
   * which the arrival of an older message can cause, since the canonical id is whichever
   * conversation is oldest.
   */
  @Test
  @SneakyThrows
  void mergingConversationsDropsTheSummaryOfTheOneThatDisappears() {
    String user = "merging";
    String otherThreadId = "<split@example.org>";
    emailBoxStorage.createEmail(mail(user, 1L, "<monday@example.org>", MONDAY));
    Email split = mail(user, 2L, "<split@example.org>", TUESDAY);
    split.setThreadId(otherThreadId);
    emailBoxStorage.createEmail(split);
    emailBoxService.saveThreadAiSummary(THREAD_ID, "The first half.", AGENT, user);
    emailBoxService.saveThreadAiSummary(otherThreadId, "The second half.", AGENT, user);

    emailBoxStorage.mergeThreads(user, THREAD_ID, List.of(otherThreadId));

    assertNull(emailBoxService.getThreadAiSummary(otherThreadId, user),
               "the id is gone, and so is what was written under it");
  }

  /**
   * The other side of a merge, and the reason nothing has to invalidate anything: the
   * surviving conversation's own summary is KEPT, and answers stale by itself, because
   * the merge gave it more messages than were counted when it was written.
   */
  @Test
  @SneakyThrows
  void theSurvivingConversationKeepsItsSummaryAndReportsItStale() {
    String user = "surviving";
    String otherThreadId = "<split@example.org>";
    emailBoxStorage.createEmail(mail(user, 1L, "<monday@example.org>", MONDAY));
    emailBoxService.saveThreadAiSummary(THREAD_ID, "One message.", AGENT, user);
    Email split = mail(user, 2L, "<split@example.org>", TUESDAY);
    split.setThreadId(otherThreadId);
    emailBoxStorage.createEmail(split);

    emailBoxStorage.mergeThreads(user, THREAD_ID, List.of(otherThreadId));

    ThreadAiSummary summary = emailBoxService.getThreadAiSummary(THREAD_ID, user);
    assertNotNull(summary, "the surviving conversation keeps what was written about it");
    assertTrue(summary.stale(), "it now holds messages that were not there when it was written");
  }

  /**
   * A merge that names the canonical id among the ids being merged — which the caller
   * is entitled to do, since it reads them all back out of the cache — must not drop
   * the very summary it is preserving.
   */
  @Test
  @SneakyThrows
  void aMergeThatIncludesItsOwnCanonicalIdKeepsThatSummary() {
    String user = "selfmerge";
    emailBoxStorage.createEmail(mail(user, 1L, "<monday@example.org>", MONDAY));
    emailBoxService.saveThreadAiSummary(THREAD_ID, "Still here.", AGENT, user);

    emailBoxStorage.mergeThreads(user, THREAD_ID, List.of(THREAD_ID, "<never-existed@example.org>"));

    assertNotNull(emailBoxService.getThreadAiSummary(THREAD_ID, user),
                  "collapsing a conversation onto itself changes nothing about it");
  }

  /**
   * Throwing the mailbox away throws the summaries away: they are keyed by thread id,
   * and the account bound here next mints its own ids from its own Message-IDs, with
   * nothing to stop one of them colliding with an id left behind.
   */
  @Test
  @SneakyThrows
  void clearingTheMailboxClearsItsSummaries() {
    String user = "disconnected";
    emailBoxStorage.createEmail(mail(user, 1L, "<monday@example.org>", MONDAY));
    emailBoxService.saveThreadAiSummary(THREAD_ID, "About to be forgotten.", AGENT, user);

    emailBoxService.deleteUserEmails(user);

    assertNull(emailBoxService.getThreadAiSummary(THREAD_ID, user), "the mail is gone and so is what was written about it");
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
   * A draft of the same conversation, as the composer saves it — dated later than every
   * message in it, which is what a draft's date is a moment after somebody has typed.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @param writtenAt when the user last typed in it
   * @return the draft to write
   */
  private Email draft(String userId, String draftLocalId, long writtenAt) {
    Date now = new Date(writtenAt);
    Email draft = new Email();
    draft.setUserId(userId);
    draft.setFolder(MailFolder.DRAFTS);
    draft.setMailHeaderId("<" + draftLocalId + "@example.org>");
    draft.setThreadId(THREAD_ID);
    draft.setInReplyTo("<monday@example.org>");
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
