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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import org.exoplatform.emailConnector.entity.EmailFolderEntity;

import jakarta.persistence.PersistenceException;

/**
 * The custom-folder registry's queries, executed by the engine on in-memory HSQLDB
 * through the real repository proxy -- because a mock suite is green with a statement
 * the engine refuses, and every query here is a statement nothing else runs before a
 * deployment does.
 * <p>
 * Two things only a real database can show are here too: that the unique index on
 * (USER_ID, REMOTE_NAME) actually refuses a second row -- the discovery upsert relies
 * on it -- and that {@code DynamicUpdate} writes the one column that changed. The
 * second is pinned on the SQL itself, through a Hibernate statement inspector, because
 * it is the property the two-writer row depends on and no assertion on values can see
 * it: a full-row UPDATE puts every value back exactly as it was read, which is correct
 * until another writer has moved one of them in between.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.exoplatform.emailConnector.dao.EmailFolderDAOTest$SqlRecorder" })
public class EmailFolderDAOTest {

  private static final String USERNAME = "alice";

  private static final String OTHER    = "bob";

  @Autowired
  private TestEntityManager   entityManager;

  @Autowired
  private EmailFolderDAO      emailFolderDAO;

  /**
   * The minimal Spring slice: the registry entity and its repository, with Boot's
   * auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailFolderEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailFolderDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * Records every SQL statement Hibernate emits, so a test can read the UPDATE back.
   */
  public static class SqlRecorder implements StatementInspector {

    static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    /**
     * Keeps the statement and hands it back unchanged.
     *
     * @param sql the statement about to run
     * @return the same statement
     */
    @Override
    public String inspect(String sql) {
      STATEMENTS.add(sql);
      return sql;
    }
  }

  /**
   * Starts every test with an empty statement log.
   */
  @BeforeEach
  void clearTheStatementLog() {
    SqlRecorder.STATEMENTS.clear();
  }

  /**
   * The per-user listing answers that user's rows only, by display name.
   */
  @Test
  void theListingIsScopedToTheUserAndOrderedByDisplayName() {
    persist(USERNAME, "Work/Zeta", "Zeta", false, false, null);
    persist(USERNAME, "Alpha", "Alpha", false, false, null);
    persist(OTHER, "Alpha", "Alpha", false, false, null);
    entityManager.clear();

    List<String> names = emailFolderDAO.findByUserId(USERNAME).stream().map(EmailFolderEntity::getDisplayName).toList();
    assertEquals(List.of("Alpha", "Zeta"), names);
  }

  /**
   * A folder id is only ever answered together with its owner: another user's id, or
   * a name registered for another user, answers nothing.
   */
  @Test
  void aRowIsOnlyFoundWithItsOwner() {
    Long id = persist(USERNAME, "Factures", "Factures", false, false, null);
    persist(OTHER, "Factures", "Factures", false, false, null);
    entityManager.clear();

    assertEquals(1, emailFolderDAO.findByIdAndUserId(id, USERNAME).size());
    assertTrue(emailFolderDAO.findByIdAndUserId(id, OTHER).isEmpty());
    assertEquals(id, emailFolderDAO.findByUserIdAndRemoteName(USERNAME, "Factures").get(0).getId());
    assertTrue(emailFolderDAO.findByUserIdAndRemoteName(USERNAME, "factures").isEmpty(), "the remote name is exact");
  }

  /**
   * The unique index the discovery upsert relies on: a second row for the same
   * (USER_ID, REMOTE_NAME) is refused by the database, not merely unexpected.
   */
  @Test
  void aDuplicateRemoteNameForOneUserIsRefusedByTheIndex() {
    persist(USERNAME, "Factures", "Factures", false, false, null);
    entityManager.flush();

    EmailFolderEntity duplicate = folder(USERNAME, "Factures", "Factures", false, false, null);
    assertThrows(PersistenceException.class, () -> {
      entityManager.persist(duplicate);
      entityManager.flush();
    });
  }

  /**
   * The sync candidates are the enabled, present rows, oldest opt-in first: a
   * disabled folder is not a candidate, a missing one is not either, and the order is
   * what lets the cap keep the earliest opt-ins when an administrator lowers it.
   */
  @Test
  void theSyncCandidatesAreTheEnabledPresentRowsOldestOptInFirst() {
    persist(USERNAME, "Late", "Late", true, false, new Date(3_000L));
    persist(USERNAME, "Early", "Early", true, false, new Date(1_000L));
    persist(USERNAME, "Middle", "Middle", true, false, new Date(2_000L));
    persist(USERNAME, "Off", "Off", false, false, null);
    persist(USERNAME, "Gone", "Gone", true, true, new Date(500L));
    entityManager.clear();

    List<String> names = emailFolderDAO.findEnabledByUserId(USERNAME).stream().map(EmailFolderEntity::getDisplayName).toList();
    assertEquals(List.of("Early", "Middle", "Late"), names);
    // The cap counts the missing one too: it holds its slot until its grace walk.
    assertEquals(4, emailFolderDAO.countEnabledByUserId(USERNAME));
  }

  /**
   * The wipe takes every row of the user and none of anybody else's.
   */
  @Test
  void theWipeIsScopedToTheUser() {
    persist(USERNAME, "A", "A", false, false, null);
    persist(USERNAME, "B", "B", true, false, new Date());
    persist(OTHER, "A", "A", false, false, null);
    entityManager.flush();

    emailFolderDAO.deleteByUserId(USERNAME);
    entityManager.clear();

    assertTrue(emailFolderDAO.findByUserId(USERNAME).isEmpty());
    assertEquals(1, emailFolderDAO.findByUserId(OTHER).size());
  }

  /**
   * Every writer's statement, executed by the engine, and every one scoped to the
   * owner: addressed to another user's id, each updates nothing. Read back after
   * clearing the persistence context, so what is asserted is the row and not the
   * instance the statement bypassed.
   */
  @Test
  void eachWriterUpdatesItsOwnColumnsAndOnlyItsOwnersRow() {
    Long id = persist(USERNAME, "Customers/Acme", "Acme", false, false, null);
    entityManager.flush();
    entityManager.clear();

    assertEquals(0, emailFolderDAO.markSeen(id, OTHER, "Stolen", ".", new Date(1L)));
    assertEquals(1, emailFolderDAO.markSeen(id, USERNAME, "ACME", "/", new Date(1_000L)));
    entityManager.clear();
    EmailFolderEntity seen = emailFolderDAO.findById(id).orElseThrow();
    assertEquals("ACME", seen.getDisplayName());
    assertEquals(1_000L, seen.getLastSeenDate().getTime());
    assertFalse(seen.isMissing());

    assertEquals(1, emailFolderDAO.markMissing(id, USERNAME));
    entityManager.clear();
    assertTrue(emailFolderDAO.findById(id).orElseThrow().isMissing());
    assertEquals(0, emailFolderDAO.markMissing(id, OTHER));

    assertEquals(1, emailFolderDAO.enableSync(id, USERNAME, new Date(2_000L)));
    assertEquals(1, emailFolderDAO.recordSnapshot(id, USERNAME, new Date(3_000L), 11L, 12L, 13L, 14L, 50));
    entityManager.clear();
    EmailFolderEntity synced = emailFolderDAO.findById(id).orElseThrow();
    assertTrue(synced.isSyncEnabled());
    assertEquals(2_000L, synced.getEnabledDate().getTime());
    assertEquals(3_000L, synced.getLastSyncDate().getTime());
    assertEquals(11L, synced.getUidValidity());
    assertEquals(50, synced.getWindowSize());

    assertEquals(1, emailFolderDAO.recordCheck(id, USERNAME, new Date(4_000L)));
    entityManager.clear();
    EmailFolderEntity checked = emailFolderDAO.findById(id).orElseThrow();
    assertEquals(4_000L, checked.getLastSyncDate().getTime());
    assertEquals(11L, checked.getUidValidity(), "a check keeps the snapshot");

    assertEquals(0, emailFolderDAO.disableSync(id, OTHER));
    assertEquals(1, emailFolderDAO.disableSync(id, USERNAME));
    entityManager.clear();
    EmailFolderEntity disabled = emailFolderDAO.findById(id).orElseThrow();
    assertFalse(disabled.isSyncEnabled());
    assertTrue(disabled.getEnabledDate() == null && disabled.getLastSyncDate() == null);
    assertTrue(disabled.getUidValidity() == null && disabled.getUidNext() == null && disabled.getMessageCount() == null
        && disabled.getHighestModSeq() == null && disabled.getWindowSize() == null, "an opt-out forgets the whole snapshot");

    assertEquals(0, emailFolderDAO.deleteByIdAndUserId(id, OTHER));
    assertEquals(1, emailFolderDAO.deleteByIdAndUserId(id, USERNAME));
    entityManager.clear();
    assertTrue(emailFolderDAO.findById(id).isEmpty());
  }

  /**
   * The in-app rename's write: its own two columns, scoped to the owner -- addressed
   * to another user's row it updates nothing, and it never touches the opt-in or the
   * sync memory (a full-row test is {@code EmailFolderStorageTest}'s; this one pins
   * the statement itself against the real engine).
   */
  @Test
  void renameUpdatesOnlyItsOwnColumnsAndOnlyTheOwnersRow() {
    Long id = persist(USERNAME, "Factures", "Factures", true, false, new Date(1_000L));
    entityManager.flush();
    entityManager.clear();

    assertEquals(0, emailFolderDAO.renameFolder(id, OTHER, "Stolen", "Stolen"));
    assertEquals(1, emailFolderDAO.renameFolder(id, USERNAME, "Invoices", "Invoices"));
    entityManager.clear();

    EmailFolderEntity renamed = emailFolderDAO.findById(id).orElseThrow();
    assertEquals("Invoices", renamed.getRemoteName());
    assertEquals("Invoices", renamed.getDisplayName());
    assertTrue(renamed.isSyncEnabled(), "a rename never touches the opt-in");
  }

  /**
   * The rename's own pin on the SQL: only REMOTE_NAME and DISPLAY_NAME are set, never
   * SYNC_ENABLED nor any snapshot column -- the same {@code DynamicUpdate} guarantee
   * {@link #changingOneColumnUpdatesThatColumnOnly} pins for a single-field write,
   * checked here for the rename's OWN two-column statement.
   */
  @Test
  void renamesOwnUpdateNamesOnlyItsTwoColumns() {
    Long id = persist(USERNAME, "Factures", "Factures", true, false, new Date(1_000L));
    entityManager.flush();
    entityManager.clear();
    SqlRecorder.STATEMENTS.clear();

    emailFolderDAO.renameFolder(id, USERNAME, "Invoices", "Invoices");

    String update = SqlRecorder.STATEMENTS.stream()
                                          .filter(sql -> sql.toLowerCase().startsWith("update"))
                                          .reduce((first, second) -> second)
                                          .orElseThrow(() -> new AssertionError("no UPDATE was emitted"));
    String setClause = update.toLowerCase().substring(update.toLowerCase().indexOf(" set ") + 5,
                                                      update.toLowerCase().indexOf(" where "));
    assertTrue(setClause.contains("remote_name"), update);
    assertTrue(setClause.contains("display_name"), update);
    assertFalse(setClause.contains("sync_enabled"), "a rename must never touch the opt-in: " + update);
    assertEquals(2, setClause.split(",").length, "exactly the two name columns: " + update);
  }

  /**
   * The two-writer pin: changing one column of a managed row flushes an UPDATE of
   * that column and that column only. Without {@code DynamicUpdate} the statement
   * sets every column, and the settings screen's read-modify-save would put back the
   * sync checkpoint the job wrote in between -- silently, and only under load.
   */
  @Test
  void changingOneColumnUpdatesThatColumnOnly() {
    Long id = persist(USERNAME, "Factures", "Factures", true, false, new Date(1_000L));
    entityManager.flush();
    entityManager.clear();
    SqlRecorder.STATEMENTS.clear();

    EmailFolderEntity managed = emailFolderDAO.findById(id).orElseThrow();
    managed.setLastSyncDate(new Date(2_000L));
    entityManager.flush();

    String update = SqlRecorder.STATEMENTS.stream()
                                          .filter(sql -> sql.toLowerCase().startsWith("update"))
                                          .reduce((first, second) -> second)
                                          .orElseThrow(() -> new AssertionError("no UPDATE was emitted"));
    String setClause = update.toLowerCase().substring(update.toLowerCase().indexOf(" set ") + 5,
                                                      update.toLowerCase().indexOf(" where "));
    assertTrue(setClause.contains("last_sync_date"), update);
    assertFalse(setClause.contains("sync_enabled"), "a full-row UPDATE would carry SYNC_ENABLED too: " + update);
    assertEquals(1, setClause.split(",").length, "exactly one column: " + update);
  }

  // ---------------------------------------------------------------------------------
  // Delegated folders (EXO-90457): own-folder reads never see them
  // ---------------------------------------------------------------------------------

  /**
   * The own-mailbox listing, the enabled candidates and the cap's count exclude every
   * row that carries a delegation id -- the folders of a mailbox shared with the user
   * are the user's rows (same USER_ID) but not the user's own folders. Pinned on the
   * real engine because the reconcile of the discovery walk marks missing, then
   * deletes, whatever the own listing returns that the walk did not see: a delegated
   * row in that listing is a delegated row deleted at the next walk.
   */
  @Test
  void ownFolderReadsExcludeDelegatedRows() {
    Long own = persist(USERNAME, "Factures", "Factures", true, false, new Date(1_000L));
    Long delegated = persistDelegated(USERNAME, "Other Users/anne/INBOX", 42L, true);
    entityManager.clear();

    List<EmailFolderEntity> listing = emailFolderDAO.findByUserId(USERNAME);
    assertEquals(1, listing.size(), "the own listing");
    assertEquals(own, listing.get(0).getId());
    List<EmailFolderEntity> enabled = emailFolderDAO.findEnabledByUserId(USERNAME);
    assertEquals(1, enabled.size(), "the custom-folder rotation's candidates");
    assertEquals(own, enabled.get(0).getId());
    assertEquals(1, emailFolderDAO.countEnabledByUserId(USERNAME), "the custom-folder cap");

    List<EmailFolderEntity> byDelegation = emailFolderDAO.findByUserIdAndDelegationId(USERNAME, 42L);
    assertEquals(1, byDelegation.size(), "the per-delegation listing");
    assertEquals(delegated, byDelegation.get(0).getId());
    assertTrue(emailFolderDAO.findByUserIdAndDelegationId(OTHER, 42L).isEmpty(), "scoped to the grantee");
    assertEquals(1, emailFolderDAO.findByIdAndUserId(delegated, USERNAME).size(),
                 "the by-id read stays total: a CUSTOM:<id> key of a delegated folder is still the user's row");
    assertEquals(1, emailFolderDAO.findByUserIdAndRemoteName(USERNAME, "Other Users/anne/INBOX").size(),
                 "and so does the upsert lookup, or the unique key would refuse the walk");
  }

  /**
   * Adopting a row the walk registered: the delegation id and the type move, the
   * opt-in and the snapshot do not; only the owner's row.
   */
  @Test
  void adoptingARowSetsItsDelegationAndTypeOnly() {
    Long id = persist(USERNAME, "Other Users/anne/INBOX", "INBOX", true, false, new Date(1_000L));
    entityManager.clear();

    assertEquals(0, emailFolderDAO.adoptAsDelegated(id, OTHER, 42L, "DELEGATED_INBOX"), "someone else's row");
    assertEquals(1, emailFolderDAO.adoptAsDelegated(id, USERNAME, 42L, "DELEGATED_INBOX"));

    EmailFolderEntity adopted = emailFolderDAO.findById(id).orElseThrow();
    assertEquals(42L, adopted.getDelegationId());
    assertEquals("DELEGATED_INBOX", adopted.getType());
    assertTrue(adopted.isSyncEnabled(), "the opt-in the user had is kept");
    assertEquals(new Date(1_000L), adopted.getEnabledDate());
    assertTrue(emailFolderDAO.findByUserId(USERNAME).isEmpty(), "and it left the own listing");
  }

  /**
   * The purge of one shared mailbox's folders, by grantee and delegation.
   */
  @Test
  void theDelegatedPurgeIsByGranteeAndDelegation() {
    persistDelegated(USERNAME, "Other Users/anne/INBOX", 42L, false);
    persistDelegated(USERNAME, "Other Users/anne/Sent", 42L, false);
    persistDelegated(USERNAME, "Other Users/carol/INBOX", 43L, false);
    persistDelegated(OTHER, "Other Users/anne/INBOX", 42L, false);
    entityManager.clear();

    assertEquals(2, emailFolderDAO.deleteByUserIdAndDelegationId(USERNAME, 42L));

    assertEquals(1, emailFolderDAO.findByUserIdAndDelegationId(USERNAME, 43L).size());
    assertEquals(1, emailFolderDAO.findByUserIdAndDelegationId(OTHER, 42L).size());
  }

  /**
   * EXO-90553 -- the new-mail boundary of a shared INBOX, on HSQLDB: the baseline is
   * taken once, only where there is none; the claim of a range goes to exactly one of
   * two callers that read the same boundary; a silent move and a reset need the value
   * read to still be there; and a row of the user's own mailbox never takes one.
   */
  @Test
  void theSharedInboxBoundaryIsTakenOnceAndOnlyOnADelegatedRow() {
    Long shared = persistDelegated(USERNAME, "Other Users/anne/INBOX", 42L, true);
    Long own = persist(USERNAME, "Factures", "Factures", true, false, new Date(1_000L));
    entityManager.clear();

    assertEquals(1, emailFolderDAO.initialiseNotifiedUid(shared, USERNAME, 10L), "the baseline, where there is none");
    assertEquals(0, emailFolderDAO.initialiseNotifiedUid(shared, USERNAME, 99L), "and only there");
    assertEquals(0, emailFolderDAO.initialiseNotifiedUid(own, USERNAME, 10L), "never on the user's own folder");
    assertEquals(0, emailFolderDAO.initialiseNotifiedUid(shared, OTHER, 10L), "never on another user's row");

    assertEquals(1, emailFolderDAO.advanceNotifiedUid(shared, USERNAME, 10L, 12L), "the first claim of (10, 12] takes it");
    assertEquals(0, emailFolderDAO.advanceNotifiedUid(shared, USERNAME, 10L, 12L), "the second finds it taken");
    assertEquals(0, emailFolderDAO.advanceNotifiedUid(shared, USERNAME, 10L, 20L), "and so does a later claim from the same stale read");
    assertEquals(0, emailFolderDAO.advanceNotifiedUid(shared, USERNAME, 12L, 12L), "an empty range is no claim");
    assertEquals(0, emailFolderDAO.advanceNotifiedUid(shared, USERNAME, 12L, 11L), "a claim never moves it down");

    assertEquals(0, emailFolderDAO.replaceNotifiedUid(shared, USERNAME, 10L, 5L), "a silent move from a stale read is refused");
    assertEquals(1, emailFolderDAO.replaceNotifiedUid(shared, USERNAME, 12L, 5L), "a re-baseline may go down");
    assertEquals(1, emailFolderDAO.replaceNotifiedUid(shared, USERNAME, 5L, null), "and the reset clears it");
    entityManager.clear();
    assertNull(emailFolderDAO.findByIdAndUserId(shared, USERNAME).get(0).getNotifiedUid());
    assertNull(emailFolderDAO.findByIdAndUserId(own, USERNAME).get(0).getNotifiedUid());
  }

  /**
   * Persists one delegated folder row.
   *
   * @param userId the grantee
   * @param remoteName the Other Users path
   * @param delegationId the delegation
   * @param enabled the opt-in
   * @return the row id
   */
  private Long persistDelegated(String userId, String remoteName, long delegationId, boolean enabled) {
    EmailFolderEntity entity = folder(userId, remoteName, "INBOX", enabled, false, enabled ? new Date(2_000L) : null);
    entity.setType("DELEGATED_INBOX");
    entity.setDelegationId(delegationId);
    return entityManager.persistAndFlush(entity).getId();
  }

  /**
   * Persists one registry row.
   *
   * @param userId the owner
   * @param remoteName the IMAP full name
   * @param displayName the last segment
   * @param enabled the opt-in
   * @param missing whether the last walk missed it
   * @param enabledDate when it was opted in
   * @return the row id
   */
  private Long persist(String userId, String remoteName, String displayName, boolean enabled, boolean missing, Date enabledDate) {
    return entityManager.persistAndFlush(folder(userId, remoteName, displayName, enabled, missing, enabledDate)).getId();
  }

  /**
   * Builds one registry row.
   *
   * @param userId the owner
   * @param remoteName the IMAP full name
   * @param displayName the last segment
   * @param enabled the opt-in
   * @param missing whether the last walk missed it
   * @param enabledDate when it was opted in
   * @return the unsaved entity
   */
  private EmailFolderEntity folder(String userId,
                                   String remoteName,
                                   String displayName,
                                   boolean enabled,
                                   boolean missing,
                                   Date enabledDate) {
    EmailFolderEntity entity = new EmailFolderEntity();
    entity.setUserId(userId);
    entity.setRemoteName(remoteName);
    entity.setDisplayName(displayName);
    entity.setDelimiter("/");
    entity.setType("CUSTOM");
    entity.setSyncEnabled(enabled);
    entity.setEnabledDate(enabledDate);
    entity.setMissing(missing);
    entity.setDiscoveredDate(new Date());
    entity.setLastSeenDate(new Date());
    return entity;
  }
}
