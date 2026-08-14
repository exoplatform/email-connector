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
package org.exoplatform.emailConnector.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.entity.EmailThreadAiSummaryEntity;
import org.exoplatform.emailConnector.model.MailFolder;

/**
 * What the summary table promises at the persistence level, on a real database — the
 * shipped Liquibase changelog over in-memory HSQLDB with {@code ddl-auto=none}, the rig
 * {@link EmailBoxThreadSummaryDAOTest} documents.
 * <p>
 * Three things here can only be asked of a database, and each has cost this schema a
 * defect before: whether an index a changeset declares actually exists (a table created
 * without its sequence, 1.0.0-43), whether a column can hold what its code puts in it,
 * and whether an ordering that looks total in JPQL is total in SQL. The staleness rule
 * built on top of them is exercised end to end elsewhere, in
 * {@code EmailThreadAiSummaryTest}.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
public class EmailBoxThreadAiSummaryDAOTest {

  private static final String            THREAD_ID = "<monday@example.org>";

  private static final long              MONDAY    = 1_000_000_000_000L;

  @Autowired
  private TestEntityManager              entityManager;

  @Autowired
  private EmailThreadAiSummaryDAO        emailThreadAiSummaryDAO;

  @Autowired
  private EmailBoxDAO                    emailBoxDAO;

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
   * One summary per conversation per mailbox, enforced by the database rather than by
   * whoever remembers to look first.
   * <p>
   * Asserted because the whole write path assumes it: the storage layer reads the
   * existing row and updates it, which is the right thing to do exactly as long as
   * there cannot be a second one to miss. If changeset 1.0.0-48's index were declared
   * and not created — a table shipped without the thing beside it, which this schema
   * has already lived through once — nothing else in this module would notice, and the
   * symptom in production would be a conversation quietly holding two summaries and
   * serving whichever the query happened to return first.
   */
  @Test
  void aConversationCannotHoldTwoSummaries() {
    emailThreadAiSummaryDAO.saveAndFlush(summary("alice", THREAD_ID, "The first."));

    // The second row a concurrent write would insert: a NEW row rather than an update
    // of the first, which is what two producers racing on one conversation produce.
    EmailThreadAiSummaryEntity second = summary("alice", THREAD_ID, "A second, which must not be stored.");

    assertThrows(DataIntegrityViolationException.class,
                 () -> emailThreadAiSummaryDAO.saveAndFlush(second),
                 "the database is what guarantees there is one current summary per conversation");
  }

  /**
   * Two people can each have their own summary of a conversation whose id they share,
   * which two subscribers to one mailing list legitimately do: the id is minted from
   * Message-IDs they both received, and each of them has their own copy of the thread —
   * which may be missing the messages they were not sent.
   */
  @Test
  void twoMailboxesMayEachSummariseTheSameConversationId() {
    entityManager.persist(summary("veronika", THREAD_ID, "Véronika's copy."));
    entityManager.persist(summary("gianni", THREAD_ID, "Gianni's copy."));
    entityManager.flush();

    assertEquals("Véronika's copy.", emailThreadAiSummaryDAO.findByUserIdAndThreadId("veronika", THREAD_ID).get(0).getSummary());
    assertEquals("Gianni's copy.", emailThreadAiSummaryDAO.findByUserIdAndThreadId("gianni", THREAD_ID).get(0).getSummary());
  }

  /**
   * A summary is prose about a whole conversation, not a label, and the column has to
   * take it whole. Written at four thousand characters — comfortably past the varchar
   * widths the rest of this schema uses — because a column silently too narrow fails at
   * the write, in production, on the first genuinely long thread.
   */
  @Test
  void aLongSummaryIsStoredWhole() {
    String prose = "Véronika and Gianni went back and forth about the contract. ".repeat(70);
    entityManager.persist(summary("verbose", THREAD_ID, prose));
    entityManager.flush();
    entityManager.clear();

    assertEquals(prose,
                 emailThreadAiSummaryDAO.findByUserIdAndThreadId("verbose", THREAD_ID).get(0).getSummary(),
                 "a summary comes back exactly as it was written, however long");
  }

  /**
   * Dropping a mailbox's summaries drops that mailbox's and nobody else's.
   */
  @Test
  void clearingOneMailboxLeavesTheOthersAlone() {
    entityManager.persist(summary("leaving", THREAD_ID, "Going."));
    entityManager.persist(summary("staying", THREAD_ID, "Staying."));
    entityManager.flush();

    emailThreadAiSummaryDAO.deleteByUserId("leaving");
    entityManager.clear();

    assertTrue(emailThreadAiSummaryDAO.findByUserIdAndThreadId("leaving", THREAD_ID).isEmpty());
    assertEquals(1, emailThreadAiSummaryDAO.findByUserIdAndThreadId("staying", THREAD_ID).size());
  }

  /**
   * The newest message of a conversation is the same message on two consecutive reads
   * when two of its messages share a received date — which they routinely do, a mail
   * and its copy in another folder being the everyday case.
   * <p>
   * Asserted on a real database because this is exactly the kind of thing that is true
   * of the JPQL and not of the SQL: an {@code ORDER BY} on the date alone leaves the
   * tie to the plan, and a fingerprint whose "newest message" changes without any mail
   * arriving would report a summary stale and fresh by turns, for no reason a user
   * could ever see.
   */
  @Test
  void theNewestMessageIsSettledEvenWhenDatesTie() {
    emailBoxDAO.save(mail("tied", 41L, "<a@example.org>", MailFolder.INBOX));
    emailBoxDAO.save(mail("tied", 77L, "<b@example.org>", MailFolder.INBOX));
    emailBoxDAO.save(mail("tied", 12L, "<c@example.org>", MailFolder.INBOX));
    entityManager.flush();
    entityManager.clear();

    List<Object[]> first = emailBoxDAO.findThreadFingerprintRows("tied", THREAD_ID);
    List<Object[]> second = emailBoxDAO.findThreadFingerprintRows("tied", THREAD_ID);

    assertEquals(3, first.size());
    assertEquals(77L, ((Number) first.get(0)[1]).longValue(), "the tie is broken by the UID, highest first");
    assertEquals(first.get(0)[1], second.get(0)[1], "and it is broken the same way every time");
  }

  /**
   * A summary row, with the fingerprint left out: these tests are about the table, and
   * the fingerprint's meaning is settled where the staleness rule lives.
   *
   * @param userId the mailbox owner
   * @param threadId the conversation id
   * @param text the written summary
   * @return the row to store
   */
  private EmailThreadAiSummaryEntity summary(String userId, String threadId, String text) {
    EmailThreadAiSummaryEntity entity = new EmailThreadAiSummaryEntity();
    entity.setUserId(userId);
    entity.setThreadId(threadId);
    entity.setSummary(text);
    entity.setMessageCount(1);
    entity.setNewestMessageKey("INBOX:1");
    entity.setAgentNameId("SUMMARIZE_THREAD");
    entity.setCreatedDate(new Date());
    return entity;
  }

  /**
   * A cached message of the conversation, every one of them delivered at the very same
   * instant — which is the point of the one test that uses this.
   *
   * @param userId the mailbox owner
   * @param mailRemoteId its IMAP UID
   * @param messageId its Message-ID
   * @param folder the folder it is cached in
   * @return the row to store
   */
  private EmailBoxEntity mail(String userId, long mailRemoteId, String messageId, String folder) {
    EmailBoxEntity mail = new EmailBoxEntity();
    mail.setUserId(userId);
    mail.setFolder(folder);
    mail.setMailRemoteId(mailRemoteId);
    mail.setMailHeaderId(messageId);
    mail.setThreadId(THREAD_ID);
    mail.setSender("Veronika,veronika@example.org");
    mail.setSubject("A conversation");
    mail.setBody("<p>a message</p>");
    mail.setReceivedDate(new Date(MONDAY));
    mail.setRead(true);
    mail.setRecent(false);
    return mail;
  }
}
