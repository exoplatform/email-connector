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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;

/**
 * Real-database coverage of the cached message's IS_HTML column, on in-memory HSQLDB.
 * <p>
 * The column carries three states and the difference between two of them is the whole
 * design: true and false are what the message said about its own body, and null is "this
 * row was cached before anyone asked". Only a real round trip through SQL can show that
 * null survives as null rather than arriving back as the primitive false that a mocked
 * DAO would happily hand over — and were it to arrive as false, every HTML mail already
 * in the cache would render as escaped source.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop" })
public class EmailBoxDAOTest {

  private static final String USERNAME = "alice";

  @Autowired
  private TestEntityManager   entityManager;

  @Autowired
  private EmailBoxDAO         emailBoxDAO;

  /**
   * The minimal Spring slice: the mailbox entities and their repository, with Boot's
   * auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * Each of the three states makes it to the database and back unchanged.
   */
  @Test
  void bodyFormatSurvivesTheRoundTrip() {
    Long htmlId = persistEmail(1L, "<p>rich</p>", Boolean.TRUE);
    Long plainId = persistEmail(2L, "just text", Boolean.FALSE);
    Long legacyId = persistEmail(3L, "cached before the column existed", null);
    entityManager.clear();

    assertTrue(emailBoxDAO.findById(htmlId).orElseThrow().getHtml());
    assertFalse(emailBoxDAO.findById(plainId).orElseThrow().getHtml());
    assertNull(emailBoxDAO.findById(legacyId).orElseThrow().getHtml(), "an unanswered row must stay unanswered");
  }

  /**
   * The body and its format travel together: reading one back without the other would
   * describe some other message.
   */
  @Test
  void theFormatBelongsToTheBodyItDescribes() {
    Long id = persistEmail(4L, "<div dir=\"ltr\">Hello</div>", Boolean.TRUE);
    entityManager.clear();

    EmailBoxEntity reloaded = emailBoxDAO.findById(id).orElseThrow();
    assertEquals("<div dir=\"ltr\">Hello</div>", reloaded.getBody());
    assertTrue(reloaded.getHtml());
  }

  /**
   * The "whole mailbox as it may be SHOWN" read leaves out every hidden folder in one
   * bound list. Executed here against the engine because nothing in the product calls
   * it yet — it is the read the total one's javadoc tells a future caller to use, so
   * its grammar ({@code NOT IN} over a collection parameter) has to be known to parse
   * and to answer right before that caller exists, not after.
   */
  @Test
  void theShowableReadLeavesOutEveryHiddenFolderAtOnce() {
    persistEmail(10L, MailFolder.INBOX, "kept", Boolean.TRUE);
    persistEmail(11L, MailFolder.SENT, "also kept", Boolean.TRUE);
    persistEmail(12L, MailFolder.TRASH, "deleted", Boolean.TRUE);
    persistEmail(13L, MailFolder.JUNK, "quarantined", Boolean.TRUE);
    entityManager.clear();

    List<Long> shown = emailBoxDAO.findByUserIdExcludingFoldersWithAttachments(USERNAME, MailFolder.HIDDEN_FOLDERS)
                                  .stream()
                                  .map(EmailBoxEntity::getMailRemoteId)
                                  .sorted()
                                  .toList();

    assertEquals(List.of(10L, 11L), shown, "the inbox and sent rows are shown; the deleted and the quarantined ones are not");
  }

  /**
   * The folder-scoped id lookup behind the undo of a move answers the one folder it is
   * asked about, for the one user, and only the rows carrying that Message-ID --
   * executed against the engine because a lookup that answered another folder's row
   * would have the undo delete the row of a message that is still there.
   */
  @Test
  void theIdLookupByMessageIdIsScopedToItsFolderAndItsOwner() {
    Long inFactures = persistEmailCarrying(20L, "CUSTOM:1", "<a@host>");
    persistEmailCarrying(21L, MailFolder.INBOX, "<a@host>");
    persistEmailCarrying(22L, "CUSTOM:1", "<b@host>");
    entityManager.clear();

    assertEquals(List.of(inFactures), emailBoxDAO.findIdsByMailHeaderIdAndUserIdAndFolder("<a@host>", USERNAME, "CUSTOM:1"));
    assertEquals(List.of(), emailBoxDAO.findIdsByMailHeaderIdAndUserIdAndFolder("<a@host>", "someone-else", "CUSTOM:1"));
    assertEquals(List.of(), emailBoxDAO.findIdsByMailHeaderIdAndUserIdAndFolder("<c@host>", USERNAME, "CUSTOM:1"));
  }

  /**
   * The read the Favorites drawer is reconciled from: the owner's starred rows in every
   * folder but the excluded ones, as {@code [id, folder, mailHeaderId, mailRemoteId]} -- executed
   * against the engine for its {@code NOT IN} over a bound list and its projection.
   */
  @Test
  void theStarredKeysReadLeavesOutTheExcludedFoldersAndTheUnstarredRows() {
    Long inInbox = starredRow(30L, MailFolder.INBOX, "<a@host>");
    Long inProjets = starredRow(31L, "CUSTOM:6", "<b@host>");
    starredRow(32L, MailFolder.TRASH, "<c@host>");
    starredRow(33L, MailFolder.JUNK, "<d@host>");
    starredRow(34L, MailFolder.ALL_MAIL, "<a@host>");
    starredRow(36L, MailFolder.DRAFTS, "<f@host>");
    persistEmailCarrying(35L, MailFolder.ARCHIVE, "<e@host>");
    entityManager.clear();

    List<Object[]> rows = emailBoxDAO.findStarredKeysByUserIdExcludingFolders(USERNAME, MailFolder.NOT_FAVORITED_FOLDERS);

    assertEquals(List.of(inInbox, inProjets), rows.stream().map(row -> (Long) row[0]).sorted().toList(),
                 "the starred inbox and user-folder rows; not Trash, Spam, All Mail, Drafts, nor the unstarred archive row");
    Object[] projets = rows.stream().filter(row -> inProjets.equals(row[0])).findFirst().orElseThrow();
    assertEquals("CUSTOM:6", projets[1]);
    assertEquals("<b@host>", projets[2]);
    assertEquals(31L, projets[3]);
    assertTrue(emailBoxDAO.findStarredKeysByUserIdExcludingFolders("bob", MailFolder.NOT_FAVORITED_FOLDERS).isEmpty(),
               "another user's read answers none of these rows");
  }

  /**
   * The read a favorite's removal unstars through: the owner's starred rows carrying one
   * Message-ID, in every folder but the excluded ones -- executed against the engine for
   * the equality on the Message-ID column beside the {@code NOT IN} and the projection.
   */
  @Test
  void theStarredCopiesReadKeepsOneMessageIdOnly() {
    Long inInbox = starredRow(40L, MailFolder.INBOX, "<a@host>");
    Long inProjets = starredRow(41L, "CUSTOM:6", "<a@host>");
    starredRow(42L, MailFolder.SENT, "<other@host>");
    starredRow(43L, MailFolder.ALL_MAIL, "<a@host>");
    persistEmailCarrying(44L, MailFolder.ARCHIVE, "<a@host>");
    entityManager.clear();

    List<Object[]> rows = emailBoxDAO.findStarredKeysByUserIdAndMailHeaderIdExcludingFolders(USERNAME,
                                                                                          "<a@host>",
                                                                                          MailFolder.NOT_FAVORITED_FOLDERS);

    assertEquals(List.of(inInbox, inProjets), rows.stream().map(row -> (Long) row[0]).sorted().toList(),
                 "the two starred copies of the message; not another message's row, the All Mail copy, nor the unstarred one");
    Object[] projets = rows.stream().filter(row -> inProjets.equals(row[0])).findFirst().orElseThrow();
    assertEquals("CUSTOM:6", projets[1]);
    assertEquals("<a@host>", projets[2]);
    assertEquals(41L, projets[3]);
    assertTrue(emailBoxDAO.findStarredKeysByUserIdAndMailHeaderIdExcludingFolders("bob", "<a@host>", MailFolder.NOT_FAVORITED_FOLDERS)
                          .isEmpty(),
               "another user's read answers none of these rows");
  }

  /**
   * A starred row of the test user.
   *
   * @param remoteId its UID
   * @param folder its folder
   * @param mailHeaderId its Message-ID
   * @return its technical id
   */
  private Long starredRow(long remoteId, String folder, String mailHeaderId) {
    Long id = persistEmailCarrying(remoteId, folder, mailHeaderId);
    EmailBoxEntity email = entityManager.find(EmailBoxEntity.class, id);
    email.setStarred(true);
    entityManager.persist(email);
    entityManager.flush();
    return id;
  }

  /**
   * EXO-90555 -- the (UID, id) pairs a search result page is decorated with, run on
   * HSQLDB: the cached UIDs of the page in THAT folder of THAT user, each with its own
   * row's id -- never the row of another folder numbered alike, never another user's.
   */
  @Test
  void theCachedIdsOfAPageAreScopedToTheirFolderAndOwner() {
    Long inInbox = persistEmail(8L, MailFolder.INBOX, "b", Boolean.FALSE);
    persistEmail(8L, MailFolder.SENT, "b", Boolean.FALSE);
    EmailBoxEntity someoneElses = entityManager.find(EmailBoxEntity.class, persistEmail(9L, MailFolder.INBOX, "b", Boolean.FALSE));
    someoneElses.setUserId("bob");
    entityManager.persist(someoneElses);
    entityManager.flush();
    entityManager.clear();

    List<Object[]> rows = emailBoxDAO.findCachedIdsByMailRemoteIds(USERNAME, MailFolder.INBOX, List.of(8L, 9L, 10L));

    assertEquals(1, rows.size());
    assertEquals(8L, rows.get(0)[0]);
    assertEquals(inInbox, rows.get(0)[1]);
  }

  /**
   * Persists one cached message in a given folder, pinned to a Message-ID.
   *
   * @param remoteId the IMAP UID, within that folder
   * @param folder the {@link MailFolder} discriminator
   * @param mailHeaderId the Message-ID the row remembers
   * @return the row's generated id
   */
  /**
   * Stack review #437-1 -- the sweep's query, run on HSQLDB: the distinct custom and
   * shared-mailbox folder keys of one user's cache, never a built-in folder, never
   * somebody else's.
   */
  @Test
  void theCustomFolderKeysAreDistinctAndTheUsersOwn() {
    persistEmail(30L, "CUSTOM:1", "a", Boolean.FALSE);
    persistEmail(31L, "CUSTOM:1", "b", Boolean.FALSE);
    persistEmail(32L, "CUSTOM:9", "c", Boolean.FALSE);
    persistEmail(33L, MailFolder.INBOX, "d", Boolean.FALSE);
    EmailBoxEntity other = entityManager.find(EmailBoxEntity.class, persistEmail(34L, "CUSTOM:7", "e", Boolean.FALSE));
    other.setUserId("bob");
    entityManager.persist(other);
    entityManager.flush();
    entityManager.clear();

    assertEquals(List.of("CUSTOM:1", "CUSTOM:9"), emailBoxDAO.findCustomFolderKeysByUserId(USERNAME).stream().sorted().toList());
  }

  private Long persistEmailCarrying(long remoteId, String folder, String mailHeaderId) {
    Long id = persistEmail(remoteId, folder, "body", Boolean.FALSE);
    EmailBoxEntity email = entityManager.find(EmailBoxEntity.class, id);
    email.setMailHeaderId(mailHeaderId);
    entityManager.persist(email);
    entityManager.flush();
    return id;
  }

  /**
   * The listed read of the "Suggestions" view (EXO-90851), run by the engine: the owner's
   * copies carrying one of the Message-IDs, outside the excluded folders, never a draft,
   * newest first, as the nine columns it projects -- the sender column as stored.
   */
  @Test
  void theListedReadByMessageIdsIsScopedNewestFirstAndLeavesOutExcludedFoldersAndDrafts() {
    Long older = persistEmailCarrying(30L, MailFolder.INBOX, "<a@host>");
    Long newer = persistEmailCarrying(31L, "CUSTOM:1", "<b@host>");
    persistEmailCarrying(32L, MailFolder.TRASH, "<a@host>");
    persistEmailCarrying(33L, MailFolder.INBOX, "<other@host>");
    Long draft = persistEmailCarrying(34L, "CUSTOM:2", "<b@host>");
    Long bobs = persistEmailCarrying(35L, MailFolder.INBOX, "<a@host>");
    EmailBoxEntity olderRow = entityManager.find(EmailBoxEntity.class, older);
    olderRow.setReceivedDate(new Date(1_000L));
    olderRow.setSubject("Older");
    olderRow.setRead(true);
    EmailBoxEntity newerRow = entityManager.find(EmailBoxEntity.class, newer);
    newerRow.setReceivedDate(new Date(2_000L));
    newerRow.setStarred(true);
    entityManager.find(EmailBoxEntity.class, draft).setDraftLocalId("draft-1");
    entityManager.find(EmailBoxEntity.class, bobs).setUserId("bob");
    entityManager.flush();
    entityManager.clear();

    List<Object[]> rows = emailBoxDAO.findListedByUserIdAndMailHeaderIds(USERNAME,
                                                                         List.of("<a@host>", "<b@host>"),
                                                                         List.of(MailFolder.TRASH, MailFolder.JUNK));

    assertEquals(List.of(newer, older), rows.stream().map(row -> (Long) row[0]).toList(),
                 "the owner's two listable copies, newest first; Trash, the draft, another Message-ID and bob's left out");
    Object[] olderProjected = rows.get(1);
    assertEquals(MailFolder.INBOX, olderProjected[1]);
    assertEquals("<a@host>", olderProjected[2]);
    assertEquals(30L, olderProjected[3]);
    assertEquals("Older", olderProjected[4]);
    assertEquals("Bob Smith,bob@example.org", olderProjected[5]);
    assertEquals(1_000L, ((Date) olderProjected[6]).getTime());
    assertEquals(Boolean.TRUE, olderProjected[7]);
    assertEquals(Boolean.FALSE, olderProjected[8]);
    assertEquals(Boolean.FALSE, rows.get(0)[7]);
    assertEquals(Boolean.TRUE, rows.get(0)[8]);
  }

  /**
   * The slice read of a consumer working through new mail (EXO-90418): UIDs strictly
   * between the bounds, highest first, scoped to the owner and the folder, bounded by
   * the page; and the folder's highest UID, null on an empty folder.
   */
  @Test
  void theUidSliceIsExclusiveNewestFirstAndScoped() {
    for (long uid : new long[] { 3, 5, 7, 9, 11 }) {
      persistEmail(uid, "b", Boolean.FALSE);
    }
    persistEmail(8, MailFolder.SENT, "b", Boolean.FALSE);
    EmailBoxEntity other = entityManager.find(EmailBoxEntity.class, persistEmail(10, "b", Boolean.FALSE));
    other.setUserId("bob");
    entityManager.persist(other);
    entityManager.flush();

    assertEquals(List.of(9L, 7L, 5L), emailBoxDAO.findUidsBetween(USERNAME, MailFolder.INBOX, 3, 11, PageRequest.of(0, 10)));
    assertEquals(List.of(11L, 9L), emailBoxDAO.findUidsBetween(USERNAME, MailFolder.INBOX, 0, Long.MAX_VALUE, PageRequest.of(0, 2)));
    assertEquals(11L, emailBoxDAO.findMaxUid(USERNAME, MailFolder.INBOX));
    assertEquals(8L, emailBoxDAO.findMaxUid(USERNAME, MailFolder.SENT));
    assertNull(emailBoxDAO.findMaxUid(USERNAME, MailFolder.ARCHIVE));
  }

  /**
   * The "/mail" link picker's read (EXO-90715): the owner's rows only, matched on
   * the subject or on either half of the sender, case-insensitively, newest first,
   * with Drafts, Trash and Junk never offered.
   */
  @Test
  void theLinkPickerReadIsOwnedAndLeavesOutDraftsTrashAndJunk() {
    long oldest = persistLinkable(MailFolder.INBOX, USERNAME, "Invoice 1", "Contoso Billing,billing@contoso.com", 1_000L);
    long newest = persistLinkable(MailFolder.ARCHIVE, USERNAME, "Re: INVOICE 2", "Bob,bob@example.org", 3_000L);
    long bySender = persistLinkable(MailFolder.SENT, USERNAME, "Payment", "Contoso,invoice@contoso.com", 2_000L);
    persistLinkable(MailFolder.INBOX, "bob", "Invoice of bob", "Contoso,invoice@contoso.com", 4_000L);
    persistLinkable(MailFolder.DRAFTS, USERNAME, "Invoice draft", "Alice,alice@example.com", 5_000L);
    persistLinkable(MailFolder.TRASH, USERNAME, "Invoice deleted", "Contoso,invoice@contoso.com", 6_000L);
    persistLinkable(MailFolder.JUNK, USERNAME, "Invoice spam", "Spammer,invoice@spam.test", 7_000L);
    persistLinkable(MailFolder.INBOX, USERNAME, "Lunch", "Carol,carol@example.org", 8_000L);
    entityManager.clear();

    assertEquals(List.of(newest, bySender, oldest), linkIds("invoice", 0, 20),
                 "subject or sender, any case, newest first; bob's row and the draft, trash and junk rows are never offered");
    assertEquals(List.of(bySender, oldest), linkIds("CONTOSO", 0, 20), "the sender's name and address both match");
    assertEquals(List.of(), linkIds("invoice of bob", 0, 20), "another user's mail is never returned");
  }

  /**
   * The page is cut in SQL: the limit bounds it and the offset skips the newest.
   */
  @Test
  void theLinkPickerReadHonoursOffsetAndLimit() {
    long first = persistLinkable(MailFolder.INBOX, USERNAME, "report 1", "Bob,bob@example.org", 1_000L);
    long second = persistLinkable(MailFolder.INBOX, USERNAME, "report 2", "Bob,bob@example.org", 2_000L);
    long third = persistLinkable(MailFolder.INBOX, USERNAME, "report 3", "Bob,bob@example.org", 3_000L);
    entityManager.clear();

    assertEquals(List.of(third, second), linkIds("report", 0, 2));
    assertEquals(List.of(second, first), linkIds("report", 1, 2));
    assertEquals(List.of(first), linkIds("report", 2, 2));
  }

  /**
   * A {@code %}, a {@code _} or the escape character typed by the user is matched
   * as itself, never as a wildcard.
   */
  @Test
  void theLinkPickerKeywordIsMatchedLiterally() {
    long percent = persistLinkable(MailFolder.INBOX, USERNAME, "Discount 50% now", "Bob,bob@example.org", 1_000L);
    persistLinkable(MailFolder.INBOX, USERNAME, "Discount 500 now", "Bob,bob@example.org", 2_000L);
    long underscore = persistLinkable(MailFolder.INBOX, USERNAME, "file a_b.pdf", "Bob,bob@example.org", 3_000L);
    persistLinkable(MailFolder.INBOX, USERNAME, "file axb.pdf", "Bob,bob@example.org", 4_000L);
    long bang = persistLinkable(MailFolder.INBOX, USERNAME, "Hello!% there", "Bob,bob@example.org", 5_000L);
    persistLinkable(MailFolder.INBOX, USERNAME, "Hello% there", "Bob,bob@example.org", 6_000L);
    entityManager.clear();

    assertEquals(List.of(percent), linkIds("50%", 0, 20));
    assertEquals(List.of(underscore), linkIds("a_b", 0, 20));
    assertEquals(List.of(bang), linkIds("o!%", 0, 20));
    assertFalse(linkIds("%", 0, 20).contains(underscore), "a lone percent sign is a character, not match-all");
  }

  /**
   * The ids the link picker's read answers for a keyword, through the storage's
   * own pattern builder.
   *
   * @param keyword the typed keyword
   * @param offset how many matches to skip
   * @param limit how many matches at most
   * @return the matching row ids, in the order the read answers them
   */
  private List<Long> linkIds(String keyword, int offset, int limit) {
    return emailBoxDAO.findLinkCandidatesByUserId(USERNAME,
                                                  EmailBoxStorage.LINK_EXCLUDED_FOLDERS,
                                                  EmailBoxStorage.toContainsPattern(keyword),
                                                  offset,
                                                  limit)
                      .stream()
                      .map(row -> (Long) row[0])
                      .toList();
  }

  /**
   * Persists one cached message the link picker could offer.
   *
   * @param folder the {@link MailFolder} discriminator
   * @param owner the mailbox owner
   * @param subject the subject
   * @param sender the stored {@code name,address} sender
   * @param receivedAt the reception time, in epoch milliseconds
   * @return the row's generated id
   */
  private long persistLinkable(String folder, String owner, String subject, String sender, long receivedAt) {
    EmailBoxEntity email = new EmailBoxEntity();
    email.setMailRemoteId(receivedAt);
    email.setUserId(owner);
    email.setFolder(folder);
    email.setSubject(subject);
    email.setSender(sender);
    email.setTo("Alice,alice@example.com");
    email.setCc("");
    email.setReceivedDate(new Date(receivedAt));
    email.setBody("body");
    entityManager.persist(email);
    entityManager.flush();
    return email.getId();
  }

  /**
   * Persists one cached inbox message.
   *
   * @param remoteId the IMAP UID
   * @param body the cached body
   * @param html what the message said about that body, null when it was never asked
   * @return the row's generated id
   */
  private Long persistEmail(long remoteId, String body, Boolean html) {
    return persistEmail(remoteId, MailFolder.INBOX, body, html);
  }

  /**
   * Persists one cached message in a given folder.
   *
   * @param remoteId the IMAP UID, within that folder
   * @param folder the {@link MailFolder} discriminator
   * @param body the cached body
   * @param html what the message said about that body, null when it was never asked
   * @return the row's generated id
   */
  private Long persistEmail(long remoteId, String folder, String body, Boolean html) {
    EmailBoxEntity email = new EmailBoxEntity();
    email.setMailRemoteId(remoteId);
    email.setUserId(USERNAME);
    email.setFolder(folder);
    email.setSender("Bob Smith,bob@example.org");
    email.setTo("Alice,alice@example.com");
    email.setCc("");
    email.setReceivedDate(new Date());
    email.setBody(body);
    email.setHtml(html);
    entityManager.persist(email);
    entityManager.flush();
    return email.getId();
  }
}
