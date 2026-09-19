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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.dao.EmailReadReceiptAnswerDAO;
import org.exoplatform.emailConnector.entity.EmailReadReceiptAnswerEntity;
import org.exoplatform.emailConnector.model.ReadReceiptAnswerOrigin;
import org.exoplatform.emailConnector.model.ReadReceiptState;

/**
 * The durable read-receipt answers (EXO-90435, phase 2) through the real storage and
 * DAO, over the schema the changelog builds (1.0.0-67 to -69): the unique index as the
 * at-most-once decision, under real contention; the reads by hash; the release scoped
 * to its own record; the server mirror that never overwrites an answer.
 * <p>
 * Every write is committed ({@link Propagation#NOT_SUPPORTED}, as in
 * {@code EmailSyncStateClaimConcurrencyTest}): a unique index refuses only what another
 * transaction committed, and work a test transaction rolls back is invisible to the
 * racing threads. Each test cleans up after itself for the same reason.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailReadReceiptAnswerStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EmailReadReceiptAnswerStorageTest {

  private static final String           USER       = "alice";

  private static final String           MESSAGE_ID = "<original@partner.example>";

  private static final Date             NOW        = new Date(1_800_000_000_000L);

  private static final int              CLAIMANTS  = 8;

  @Autowired
  private EmailReadReceiptAnswerStorage answerStorage;

  @Autowired
  private EmailReadReceiptAnswerDAO     answerDAO;

  /**
   * The minimal Spring slice: the add-on's entities and repositories, over Boot's
   * auto-configured in-memory database built by the changelog.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailReadReceiptAnswerEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailReadReceiptAnswerDAO.class)
  static class JpaSliceConfiguration {
  }

  /** Committed rows are removed after each test. */
  @AfterEach
  void removeTheAnswers() {
    answerDAO.deleteAll();
  }

  /**
   * The first answer of a message is recorded and read back by its Message-ID; the
   * second one, whatever it says, is refused and changes nothing.
   */
  @Test
  void theFirstAnswerIsTheOnlyOne() {
    Long first = answerStorage.claim(USER, MESSAGE_ID, ReadReceiptState.IGNORED, ReadReceiptAnswerOrigin.LOCAL, NOW);
    assertNotNull(first);
    assertNull(answerStorage.claim(USER, MESSAGE_ID, ReadReceiptState.SENT, ReadReceiptAnswerOrigin.LOCAL, NOW),
               "a second answer to the same message is refused");
    assertNull(answerStorage.claim(USER, "  " + MESSAGE_ID + " ", ReadReceiptState.SENT, ReadReceiptAnswerOrigin.LOCAL, NOW),
               "the same Message-ID, however it is padded");

    Map<String, ReadReceiptState> answers = answerStorage.findAnswers(USER, List.of(MESSAGE_ID, "<other@partner.example>"));
    assertEquals(Map.of(EmailReadReceiptAnswerStorage.messageIdHash(MESSAGE_ID), ReadReceiptState.IGNORED), answers);
    EmailReadReceiptAnswerEntity row = answerDAO.findById(first).orElseThrow();
    assertEquals(ReadReceiptAnswerOrigin.LOCAL, row.getOrigin());
    assertEquals(NOW.getTime(), row.getAnsweredDate().getTime());
    assertEquals(64, row.getMessageIdHash().length());
  }

  /**
   * The answer belongs to its user: another user's answer to the same message (two
   * people on one mailing list) is theirs, and a Message-ID differing only by case is
   * another message, even under a case-insensitive collation.
   */
  @Test
  void theKeyIsTheUserAndTheExactMessageId() {
    assertNotNull(answerStorage.claim(USER, MESSAGE_ID, ReadReceiptState.SENT, ReadReceiptAnswerOrigin.LOCAL, NOW));
    assertNotNull(answerStorage.claim("bob", MESSAGE_ID, ReadReceiptState.SENT, ReadReceiptAnswerOrigin.LOCAL, NOW));
    assertNotNull(answerStorage.claim(USER, "<ORIGINAL@partner.example>", ReadReceiptState.SENT, ReadReceiptAnswerOrigin.LOCAL, NOW));
    assertEquals(1, answerStorage.findAnswers("bob", List.of(MESSAGE_ID, "<ORIGINAL@partner.example>")).size());
  }

  /**
   * The unique index itself refuses a second row, whatever path writes it: the
   * decision does not rest on the storage's code.
   */
  @Test
  void theUniqueIndexRefusesASecondRow() {
    answerDAO.saveAndFlush(new EmailReadReceiptAnswerEntity(null, USER, "a".repeat(64), ReadReceiptState.SENT, ReadReceiptAnswerOrigin.LOCAL, NOW));
    assertThrows(DataIntegrityViolationException.class,
                 () -> answerDAO.saveAndFlush(new EmailReadReceiptAnswerEntity(null,
                                                                               USER,
                                                                               "a".repeat(64),
                                                                               ReadReceiptState.IGNORED,
                                                                               ReadReceiptAnswerOrigin.SERVER,
                                                                               NOW)));
  }

  /**
   * Eight answers to one message at the same instant -- tabs, nodes, a sync -- and
   * exactly one is recorded: the index decides under contention, not the order the
   * threads happened to start in.
   *
   * @throws Exception if a claimant thread does
   */
  @Test
  void exactlyOneOfManySimultaneousAnswersIsRecorded() throws Exception {
    CountDownLatch startLine = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(CLAIMANTS);
    try {
      List<Future<Long>> races = new ArrayList<>();
      for (int i = 0; i < CLAIMANTS; i++) {
        ReadReceiptState answer = i % 2 == 0 ? ReadReceiptState.SENT : ReadReceiptState.IGNORED;
        races.add(pool.submit(() -> {
          startLine.await();
          return answerStorage.claim(USER, MESSAGE_ID, answer, ReadReceiptAnswerOrigin.LOCAL, NOW);
        }));
      }
      startLine.countDown();
      int winners = 0;
      for (Future<Long> race : races) {
        if (race.get(30, TimeUnit.SECONDS) != null) {
          winners++;
        }
      }
      assertEquals(1, winners, "exactly one answer is recorded");
      assertEquals(1, answerDAO.count());
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * A claim given back frees the message for the next answer; the release touches its
   * own record only, never another user's.
   */
  @Test
  void aReleasedClaimFreesTheMessage() {
    Long mine = answerStorage.claim(USER, MESSAGE_ID, ReadReceiptState.SENT, ReadReceiptAnswerOrigin.LOCAL, NOW);
    Long theirs = answerStorage.claim("bob", MESSAGE_ID, ReadReceiptState.SENT, ReadReceiptAnswerOrigin.LOCAL, NOW);
    answerStorage.release("bob", mine);
    assertTrue(answerDAO.existsById(mine), "another user cannot give back my answer");

    answerStorage.release(USER, mine);
    assertTrue(answerDAO.existsById(theirs));
    assertNotNull(answerStorage.claim(USER, MESSAGE_ID, ReadReceiptState.IGNORED, ReadReceiptAnswerOrigin.LOCAL, NOW),
                  "the message can be answered again");
  }

  /**
   * The sync's mirror of $MDNSent records the messages nobody answered yet, as SENT
   * from the server, and leaves an existing answer as it is -- twice in a row costs
   * nothing the second time.
   */
  @Test
  void theServerMirrorNeverOverwritesAnAnswer() {
    answerStorage.claim(USER, MESSAGE_ID, ReadReceiptState.IGNORED, ReadReceiptAnswerOrigin.LOCAL, NOW);

    assertEquals(1, answerStorage.recordServerAnswers(USER, List.of(MESSAGE_ID, "<other@partner.example>"), NOW));
    assertEquals(0, answerStorage.recordServerAnswers(USER, List.of(MESSAGE_ID, "<other@partner.example>"), NOW));

    Map<String, ReadReceiptState> answers = answerStorage.findAnswers(USER, List.of(MESSAGE_ID, "<other@partner.example>"));
    assertEquals(ReadReceiptState.IGNORED, answers.get(EmailReadReceiptAnswerStorage.messageIdHash(MESSAGE_ID)), "kept");
    assertEquals(ReadReceiptState.SENT, answers.get(EmailReadReceiptAnswerStorage.messageIdHash("<other@partner.example>")));
    assertEquals(2, answerDAO.count());
    assertTrue(answerDAO.findAll()
                        .stream()
                        .anyMatch(row -> row.getOrigin() == ReadReceiptAnswerOrigin.SERVER && row.getState() == ReadReceiptState.SENT));
  }

  /**
   * No key for what does not name a message: nothing, blanks, or the placeholder this
   * add-on synthesizes for a message that had no Message-ID. Such messages are never
   * stored nor found.
   */
  @Test
  void onlyARealMessageIdIsAKey() {
    for (String none : Arrays.asList(null, "", "  ", "<7.alice@email-connector.local>")) {
      assertNull(EmailReadReceiptAnswerStorage.messageIdHash(none), String.valueOf(none));
      assertNull(answerStorage.claim(USER, none, ReadReceiptState.SENT, ReadReceiptAnswerOrigin.LOCAL, NOW));
    }
    assertEquals(0, answerStorage.recordServerAnswers(USER, Arrays.asList(null, "<7.alice@email-connector.local>"), NOW));
    assertTrue(answerStorage.findAnswers(USER, Arrays.asList(null, "")).isEmpty());
    assertEquals(0, answerDAO.count());
    assertNotEquals(EmailReadReceiptAnswerStorage.messageIdHash("<a@b>"), EmailReadReceiptAnswerStorage.messageIdHash("<A@b>"));
  }
}
