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
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
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
