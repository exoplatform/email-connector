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
package org.exoplatform.emailConnector.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.MailFolder;

/**
 * The conversation summary the list is built on, run against a real database
 * rather than a mocked DAO — in-memory HSQLDB, the dialect of the dev rig, on the
 * SHIPPED Liquibase changelog ({@code ddl-auto=none}), the same setup and for the
 * same reason as {@link EmailBoxDraftDAOTest}.
 * <p>
 * It is here rather than over a mock because the whole of this slice is a query.
 * {@code COUNT(DISTINCT COALESCE(...))} either works on the deployed dialect or it
 * does not, and a test that stubs the DAO to return the rows it wants proves only
 * that the mapper reads three columns — which is exactly how two production defects
 * got past 724 green tests the night before.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
public class EmailBoxThreadSummaryDAOTest {

  private static final String USERNAME = "alice";

  @Autowired
  private TestEntityManager   entityManager;

  @Autowired
  private EmailBoxDAO         emailBoxDAO;

  /**
   * The minimal Spring slice: the mail entities and their repository, over Boot's
   * auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * The case the whole slice exists for: an inbox conversation the user has started
   * a reply to. The summary has to say so — the listing itself never can, since it
   * is built from INBOX rows and the draft is a DRAFTS row.
   * <p>
   * And the count has to include it: two messages in the conversation, the mail and
   * the reply being written, which is the number Gmail shows and the number the
   * product owner asked for.
   */
  @Test
  void aConversationWithADraftReportsItAndCountsIt() {
    persist(mail("<received@example.org>", MailFolder.INBOX, 1L, "thread-1"));
    persist(draft("<mine@example.org>", "draft-1", "thread-1"));

    Object[] summary = summaryOf("thread-1");
    assertEquals(2, count(summary), "the conversation holds the mail and the reply being written");
    assertTrue(hasDraft(summary), "a conversation carrying an unsent reply must say so");
  }

  /**
   * The other side of it, and the one that would break silently: an ordinary
   * conversation reports exactly what it reported before drafts existed. The draft
   * column is a SUM over a CASE, so a conversation with no drafts must come back as
   * zero rather than as null-and-therefore-anything.
   */
  @Test
  void aConversationWithoutADraftIsUnchanged() {
    persist(mail("<first@example.org>", MailFolder.INBOX, 1L, "thread-2"));
    persist(mail("<second@example.org>", MailFolder.SENT, 2L, "thread-2"));

    Object[] summary = summaryOf("thread-2");
    assertEquals(2, count(summary), "the count is the conversation's messages across every folder");
    assertFalse(hasDraft(summary), "nothing here is a draft");
  }

  /**
   * The draft was sent. Sending drops the local row (the copy that matters is now
   * in Sent, under the very Message-ID the draft minted), so the conversation stops
   * reporting a draft on its own — nothing has to remember to clear a flag.
   * <p>
   * The count stays at two rather than falling back to one, and that is the point
   * of counting DISTINCT Message-IDs: the sent copy IS the draft, so the
   * conversation was two messages while the reply was being written and is two
   * messages now that it has gone out. The number does not jump about under the
   * user as they press Send.
   */
  @Test
  void aConversationWhoseDraftWasSentStopsReportingIt() {
    persist(mail("<received@example.org>", MailFolder.INBOX, 1L, "thread-3"));
    EmailBoxEntity draft = persist(draft("<mine@example.org>", "draft-3", "thread-3"));

    Object[] beforeSend = summaryOf("thread-3");
    assertEquals(2, count(beforeSend));
    assertTrue(hasDraft(beforeSend));

    // What sendDraft does once the message is on the wire: the row goes, and the
    // copy that landed in Sent carries the Message-ID the draft was pinned with.
    emailBoxDAO.deleteEmailsByIds(List.of(draft.getId()));
    persist(mail("<mine@example.org>", MailFolder.SENT, 2L, "thread-3"));

    Object[] afterSend = summaryOf("thread-3");
    assertEquals(2, count(afterSend), "the sent copy is the draft, not a third message");
    assertFalse(hasDraft(afterSend), "there is nothing left half-written here");
  }

  /**
   * A draft written in another mail client, which reached us with no Message-ID —
   * half-written mail legitimately has none, and the import stores what the server
   * gave it.
   * <p>
   * This is what the {@code COALESCE} onto the draft's local id is for. Without it
   * {@code COUNT(DISTINCT)} would drop the row (it ignores nulls) and the list would
   * show "Gianni, Draft" beside a count of 1 — a conversation whose participants
   * outnumber its messages.
   */
  @Test
  void aDraftWithNoMessageIdIsStillCountedAndReported() {
    persist(mail("<received@example.org>", MailFolder.INBOX, 1L, "thread-4"));
    persist(draft(null, "draft-4", "thread-4"));

    Object[] summary = summaryOf("thread-4");
    assertEquals(2, count(summary), "a draft with no Message-ID is still one of the conversation's messages");
    assertTrue(hasDraft(summary));
  }

  /**
   * The rule the count already followed and must keep following now that the
   * predicate guarding it has moved: one message cached in two folders is one
   * message. Providers whose All Mail overlaps the inbox make this the normal case,
   * not an edge one.
   */
  @Test
  void oneMessageCachedInTwoFoldersIsCountedOnce() {
    persist(mail("<received@example.org>", MailFolder.INBOX, 1L, "thread-5"));
    persist(mail("<received@example.org>", MailFolder.ALL_MAIL, 7L, "thread-5"));

    assertEquals(1, count(summaryOf("thread-5")), "the same message in two folders is still one message");
  }

  /**
   * A conversation with nothing but an unsent draft in it — a mail the user started
   * from scratch and has not sent.
   * <p>
   * It is summarised like any other, and that is harmless: the inbox listing is
   * built from INBOX rows, this conversation has none, so there is no row for the
   * summary to decorate and nothing appears. The draft belongs in Drafts, and it is
   * the folder scoping of the listing — not this query — that keeps it there.
   */
  @Test
  void aDraftOnlyConversationIsSummarisedButHasNoRowToShow() {
    persist(draft("<mine@example.org>", "draft-6", "thread-6"));

    Object[] summary = summaryOf("thread-6");
    assertEquals(1, count(summary));
    assertTrue(hasDraft(summary));
    assertTrue(emailBoxDAO.findByUserIdAndFolderWithAttachments(USERNAME, MailFolder.INBOX).isEmpty(),
               "a conversation whose only message is an unsent draft has nothing in the inbox to be listed by");
  }

  /**
   * Persists a row and flushes it, so what the aggregate reads is what the database
   * holds rather than what the persistence context is still keeping to itself.
   *
   * @param entity the row to store
   * @return the stored row, with its id
   */
  private EmailBoxEntity persist(EmailBoxEntity entity) {
    EmailBoxEntity saved = emailBoxDAO.save(entity);
    entityManager.flush();
    entityManager.clear();
    return saved;
  }

  /**
   * The summary row of one conversation, out of the summary of them all.
   *
   * @param threadId the conversation id
   * @return its {@code [threadId, messageCount, draftCount]} row
   */
  private Object[] summaryOf(String threadId) {
    Map<String, Object[]> byThread = new HashMap<>();
    for (Object[] row : emailBoxDAO.summarizeThreadsByUserId(USERNAME)) {
      byThread.put((String) row[0], row);
    }
    Object[] summary = byThread.get(threadId);
    assertTrue(summary != null, "no summary was returned for " + threadId);
    return summary;
  }

  /**
   * The message count of a summary row, read the way the storage layer reads it.
   *
   * @param summary the summary row
   * @return how many messages the conversation holds
   */
  private int count(Object[] summary) {
    return ((Number) summary[1]).intValue();
  }

  /**
   * Whether a summary row reports a draft, read the way the storage layer reads it.
   *
   * @param summary the summary row
   * @return whether the conversation carries an unsent draft
   */
  private boolean hasDraft(Object[] summary) {
    return summary[2] != null && ((Number) summary[2]).intValue() > 0;
  }

  /**
   * An ordinary cached message.
   *
   * @param messageId its Message-ID
   * @param folder the folder it is cached in
   * @param uid its IMAP UID in that folder
   * @param threadId the conversation it belongs to
   * @return the row to store
   */
  private EmailBoxEntity mail(String messageId, String folder, long uid, String threadId) {
    EmailBoxEntity mail = new EmailBoxEntity();
    mail.setUserId(USERNAME);
    mail.setFolder(folder);
    mail.setMailRemoteId(uid);
    mail.setMailHeaderId(messageId);
    mail.setThreadId(threadId);
    mail.setSender("Gianni,gianni@example.org");
    mail.setSubject("A question");
    mail.setBody("<p>well?</p>");
    mail.setReceivedDate(new Date());
    mail.setRead(true);
    mail.setRecent(false);
    return mail;
  }

  /**
   * A draft the user is writing: filed in DRAFTS, carrying the local id everything
   * about a draft is addressed by, and with no IMAP UID because it may never have
   * reached the server.
   *
   * @param messageId its Message-ID, which an imported draft may not have
   * @param draftLocalId the composer's handle on it
   * @param threadId the conversation it replies to
   * @return the row to store
   */
  private EmailBoxEntity draft(String messageId, String draftLocalId, String threadId) {
    Date now = new Date();
    EmailBoxEntity draft = new EmailBoxEntity();
    draft.setUserId(USERNAME);
    draft.setFolder(MailFolder.DRAFTS);
    draft.setMailHeaderId(messageId);
    draft.setThreadId(threadId);
    draft.setSender("Alice,alice@example.org");
    draft.setSubject("Re: A question");
    draft.setBody("<p>half a sentence</p>");
    draft.setReceivedDate(now);
    draft.setRead(true);
    draft.setRecent(false);
    draft.setDraftLocalId(draftLocalId);
    draft.setDraftState(DraftState.LOCAL_ONLY);
    draft.setDraftRevision(1L);
    draft.setDraftUpdatedDate(now);
    return draft;
  }
}
