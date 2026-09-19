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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ReadReceiptState;

/**
 * The read-receipt statements of {@link EmailBoxDAO} (EXO-90435), parsed and executed
 * on the schema the changelog builds: the claim that makes a receipt leave at most
 * once, its release, the sync's mirror of {@code $MDNSent}, and the light sync view.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
class EmailBoxReadReceiptDAOTest {

  private static final String USER       = "alice";

  private static final String MESSAGE_ID = "<original@partner.example>";

  @Autowired
  private TestEntityManager   entityManager;

  @Autowired
  private EmailBoxDAO         emailBoxDAO;

  /**
   * The minimal Spring slice: the mail entities and their repository, over Boot's
   * auto-configured in-memory database built by the changelog.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * The four columns of 1.0.0-66 round-trip, and a row written without them gets the
   * safe defaults: nothing asked, no Return-Path match.
   */
  @Test
  void theReadReceiptColumnsRoundTrip() {
    EmailBoxEntity asking = row(MailFolder.INBOX, 1L, MESSAGE_ID);
    asking.setReadReceiptRequested(true);
    asking.setReadReceiptTo("Bob <bob@partner.example>");
    asking.setReadReceiptReturnPathMatch(true);
    asking.setReadReceiptState(ReadReceiptState.IGNORED);
    Long id = emailBoxDAO.save(asking).getId();
    Long plainId = emailBoxDAO.save(row(MailFolder.INBOX, 2L, "<other@partner.example>")).getId();
    entityManager.flush();
    entityManager.clear();

    EmailBoxEntity reloaded = emailBoxDAO.findById(id).orElseThrow();
    assertTrue(reloaded.isReadReceiptRequested());
    assertEquals("Bob <bob@partner.example>", reloaded.getReadReceiptTo());
    assertTrue(reloaded.isReadReceiptReturnPathMatch());
    assertEquals(ReadReceiptState.IGNORED, reloaded.getReadReceiptState());
    EmailBoxEntity plain = emailBoxDAO.findById(plainId).orElseThrow();
    assertFalse(plain.isReadReceiptRequested());
    assertFalse(plain.isReadReceiptReturnPathMatch());
    assertNull(plain.getReadReceiptState());
  }

  /**
   * The claim answers every pending copy of the message at once, then refuses a second
   * claim; it never reaches another user's copy; the release gives back only the rows
   * still carrying that very claim.
   */
  @Test
  void theClaimAnswersEveryCopyOnceAndTheReleaseOnlyItsOwn() {
    Long inbox = emailBoxDAO.save(requesting(MailFolder.INBOX, 1L, USER)).getId();
    Long allMail = emailBoxDAO.save(requesting(MailFolder.ALL_MAIL, 7L, USER)).getId();
    Long someoneElse = emailBoxDAO.save(requesting(MailFolder.INBOX, 1L, "bob")).getId();
    entityManager.flush();
    entityManager.clear();

    assertEquals(0, emailBoxDAO.countAnsweredReadReceiptsByMailHeaderId(USER, MESSAGE_ID));
    assertEquals(2, emailBoxDAO.claimReadReceiptByMailHeaderId(USER, MESSAGE_ID, ReadReceiptState.SENT));
    assertEquals(0, emailBoxDAO.claimReadReceiptByMailHeaderId(USER, MESSAGE_ID, ReadReceiptState.IGNORED),
                 "a second answer claims nothing");
    assertEquals(2, emailBoxDAO.countAnsweredReadReceiptsByMailHeaderId(USER, MESSAGE_ID));
    entityManager.clear();
    assertEquals(ReadReceiptState.SENT, emailBoxDAO.findById(inbox).orElseThrow().getReadReceiptState());
    assertEquals(ReadReceiptState.SENT, emailBoxDAO.findById(allMail).orElseThrow().getReadReceiptState());
    assertNull(emailBoxDAO.findById(someoneElse).orElseThrow().getReadReceiptState(), "another mailbox is untouched");

    assertEquals(0, emailBoxDAO.releaseReadReceiptByMailHeaderId(USER, MESSAGE_ID, ReadReceiptState.IGNORED),
                 "only the claim that was taken is given back");
    assertEquals(2, emailBoxDAO.releaseReadReceiptByMailHeaderId(USER, MESSAGE_ID, ReadReceiptState.SENT));
    entityManager.clear();
    assertNull(emailBoxDAO.findById(inbox).orElseThrow().getReadReceiptState());
  }

  /** A row with no Message-ID is claimed and released by its own id. */
  @Test
  void aRowWithoutAMessageIdIsClaimedById() {
    Long id = emailBoxDAO.save(requesting(MailFolder.INBOX, 3L, USER, null)).getId();
    entityManager.flush();
    entityManager.clear();

    assertEquals(0, emailBoxDAO.claimReadReceiptById("bob", id, ReadReceiptState.SENT), "never another user's row");
    assertEquals(1, emailBoxDAO.claimReadReceiptById(USER, id, ReadReceiptState.SENT));
    assertEquals(0, emailBoxDAO.claimReadReceiptById(USER, id, ReadReceiptState.SENT));
    assertEquals(1, emailBoxDAO.releaseReadReceiptById(USER, id, ReadReceiptState.SENT));
  }

  /**
   * The sync's mirror moves pending requests only: not a message asking nothing, not
   * one already answered, not another folder's.
   */
  @Test
  void theMirrorMovesPendingRequestsOnly() {
    emailBoxDAO.save(requesting(MailFolder.INBOX, 1L, USER));
    EmailBoxEntity ignored = requesting(MailFolder.INBOX, 2L, USER, "<ignored@partner.example>");
    ignored.setReadReceiptState(ReadReceiptState.IGNORED);
    Long ignoredId = emailBoxDAO.save(ignored).getId();
    emailBoxDAO.save(row(MailFolder.INBOX, 3L, "<plain@partner.example>"));
    emailBoxDAO.save(requesting(MailFolder.ARCHIVE, 1L, USER, "<archived@partner.example>"));
    entityManager.flush();
    entityManager.clear();

    assertEquals(1, emailBoxDAO.markReadReceiptsAnswered(USER, MailFolder.INBOX, List.of(1L, 2L, 3L), ReadReceiptState.SENT));
    assertEquals(0, emailBoxDAO.markReadReceiptsAnswered(USER, MailFolder.INBOX, List.of(1L, 2L, 3L), ReadReceiptState.SENT),
                 "a mirrored row costs nothing the next time");
    entityManager.clear();
    assertEquals(ReadReceiptState.IGNORED, emailBoxDAO.findById(ignoredId).orElseThrow().getReadReceiptState(),
                 "an answer is never overwritten");

    // By Message-ID, the answer reaches the other copies of the message, and only them.
    assertEquals(1, emailBoxDAO.markReadReceiptsAnsweredByMailHeaderIds(USER,
                                                                        List.of("<archived@partner.example>", "<ignored@partner.example>"),
                                                                        ReadReceiptState.SENT));
    assertEquals(0, emailBoxDAO.markReadReceiptsAnsweredByMailHeaderIds("bob", List.of(MESSAGE_ID), ReadReceiptState.SENT));
    entityManager.clear();
    assertEquals(ReadReceiptState.IGNORED, emailBoxDAO.findById(ignoredId).orElseThrow().getReadReceiptState());
  }

  /** The light sync view carries the request and its answer, in the last two columns. */
  @Test
  void theSyncViewCarriesTheRequestAndItsAnswer() {
    EmailBoxEntity answered = requesting(MailFolder.INBOX, 1L, USER);
    answered.setReadReceiptState(ReadReceiptState.SENT);
    emailBoxDAO.save(answered);
    entityManager.flush();
    entityManager.clear();

    Object[] view = emailBoxDAO.findSyncViewByUserIdAndFolder(USER, MailFolder.INBOX).get(0);
    assertEquals(11, view.length);
    assertEquals(Boolean.TRUE, view[9]);
    assertEquals(ReadReceiptState.SENT, view[10]);
  }

  /**
   * A cached message asking for a receipt.
   *
   * @param folder its folder
   * @param uid its UID
   * @param userId its owner
   * @return the row
   */
  private EmailBoxEntity requesting(String folder, long uid, String userId) {
    return requesting(folder, uid, userId, MESSAGE_ID);
  }

  /**
   * A cached message asking for a receipt.
   *
   * @param folder its folder
   * @param uid its UID
   * @param userId its owner
   * @param messageId its Message-ID, may be null
   * @return the row
   */
  private EmailBoxEntity requesting(String folder, long uid, String userId, String messageId) {
    EmailBoxEntity row = row(folder, uid, messageId);
    row.setUserId(userId);
    row.setReadReceiptRequested(true);
    row.setReadReceiptTo("bob@partner.example");
    return row;
  }

  /**
   * A cached message.
   *
   * @param folder its folder
   * @param uid its UID
   * @param messageId its Message-ID, may be null
   * @return the row
   */
  private EmailBoxEntity row(String folder, long uid, String messageId) {
    EmailBoxEntity row = new EmailBoxEntity();
    row.setUserId(USER);
    row.setFolder(folder);
    row.setMailRemoteId(uid);
    row.setMailHeaderId(messageId);
    row.setSender("Bob,bob@partner.example");
    row.setSubject("Quarterly figures");
    row.setReceivedDate(new Date());
    return row;
  }
}
