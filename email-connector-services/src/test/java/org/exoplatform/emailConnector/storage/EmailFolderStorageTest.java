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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.dao.EmailFolderDAO;
import org.exoplatform.emailConnector.entity.EmailFolderEntity;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.FolderSyncSnapshot;
import org.exoplatform.emailConnector.model.MailFolderView;

/**
 * The registry's writers, each against the SHIPPED Liquibase changelog on HSQLDB and
 * with the ambient transaction taken away ({@link Propagation#NOT_SUPPORTED}), so
 * every storage call commits a transaction of its own the way it does in production
 * -- which is the shape the targeted writes were designed for. Running on the
 * changelog rather than on a generated schema is also what applies changesets
 * 1.0.0-53 to 1.0.0-55 for real: a column the entity maps and the changeset forgot
 * fails here, not on an acceptance server.
 * <p>
 * Dialect caveat: HSQLDB compares names case-sensitively, so nothing here can see the
 * MySQL collation question (a case-insensitive unique index over a case-sensitive
 * IMAP name). That one is pinned on the dialect's generated SQL in
 * {@code MasterChangelogTest#theRegistryNameKeepsItsCaseOnMySql}; index usage on a
 * real MySQL still needs a real MySQL run.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailFolderStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailFolderStorageTest {

  @Autowired
  private EmailFolderStorage emailFolderStorage;

  /**
   * The minimal Spring slice: the registry entity and its repository, with Boot's
   * auto-configured in-memory database, migrated by the shipped changelog.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailFolderEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailFolderDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * A discovered folder is registered opt-in off, and each writer touches its own
   * columns: the opt-in stamps its date, the sync stamps its memory, the opt-out
   * clears both the memory and the sync date.
   */
  @Test
  void eachWriterTouchesItsOwnColumns() {
    EmailFolder created = emailFolderStorage.createFolder(newFolder("alice", "Customers/Acme", "Acme"));
    assertNotNull(created.getId());
    assertEquals("CUSTOM:" + created.getId(), created.getKey());
    assertFalse(created.isSyncEnabled());
    assertNull(created.getSnapshot());

    emailFolderStorage.updateSyncEnabled("alice", created.getId(), true, new Date(1_000L));
    EmailFolder enabled = emailFolderStorage.getFolder("alice", created.getId());
    assertTrue(enabled.isSyncEnabled());
    assertEquals(1_000L, enabled.getEnabledDate().getTime());
    assertNull(enabled.getLastSyncDate(), "a fresh opt-in is first in the rotation");

    emailFolderStorage.updateSyncMemory("alice", created.getId(), new FolderSyncSnapshot(1L, 2L, 3L, 4L, 50), new Date(2_000L));
    EmailFolder synced = emailFolderStorage.getFolder("alice", created.getId());
    assertEquals(2_000L, synced.getLastSyncDate().getTime());
    assertEquals(new FolderSyncSnapshot(1L, 2L, 3L, 4L, 50), synced.getSnapshot());
    assertTrue(synced.isSyncEnabled(), "the sync's write left the opt-in alone");

    emailFolderStorage.updateSyncMemory("alice", created.getId(), null, new Date(3_000L));
    EmailFolder skipped = emailFolderStorage.getFolder("alice", created.getId());
    assertEquals(3_000L, skipped.getLastSyncDate().getTime());
    assertEquals(new FolderSyncSnapshot(1L, 2L, 3L, 4L, 50), skipped.getSnapshot(), "a skip keeps the snapshot");

    emailFolderStorage.updateSyncEnabled("alice", created.getId(), false, new Date(4_000L));
    EmailFolder disabled = emailFolderStorage.getFolder("alice", created.getId());
    assertFalse(disabled.isSyncEnabled());
    assertNull(disabled.getEnabledDate());
    assertNull(disabled.getSnapshot(), "an opt-out forgets the sync memory");
    assertNull(disabled.getLastSyncDate());
  }

  /**
   * The sync job's writes are guarded by the opt-in at the statement level: a
   * checkpoint or a snapshot written after an opt-out changes nothing, so a sync that
   * was in flight when the user switched the folder off cannot re-plant the memory the
   * opt-out cleared.
   */
  @Test
  void aSyncWriteAfterAnOptOutChangesNothing() {
    EmailFolder created = emailFolderStorage.createFolder(newFolder("erin", "Racing", "Racing"));
    emailFolderStorage.updateSyncEnabled("erin", created.getId(), true, new Date(1_000L));
    emailFolderStorage.updateSyncEnabled("erin", created.getId(), false, new Date(2_000L));

    emailFolderStorage.updateSyncMemory("erin", created.getId(), new FolderSyncSnapshot(1L, 2L, 3L, 4L, 50), new Date(3_000L));
    emailFolderStorage.updateSyncMemory("erin", created.getId(), null, new Date(4_000L));

    EmailFolder after = emailFolderStorage.getFolder("erin", created.getId());
    assertFalse(after.isSyncEnabled());
    assertNull(after.getSnapshot(), "the late snapshot did not land");
    assertNull(after.getLastSyncDate(), "nor the late checkpoint");
  }

  /**
   * The index of 1.0.0-54, on the shipped changelog: a second row for the same
   * (USER_ID, REMOTE_NAME) is refused by the database. This is the guarantee the
   * discovery upsert stands on, and a generated schema (the DAO test's) cannot vouch
   * for what the changeset actually created.
   */
  @Test
  void theShippedIndexRefusesADuplicateRemoteName() {
    emailFolderStorage.createFolder(newFolder("carol", "Twice", "Twice"));
    assertThrows(Exception.class, () -> emailFolderStorage.createFolder(newFolder("carol", "Twice", "Twice")));
    assertNotNull(emailFolderStorage.createFolder(newFolder("dave", "Twice", "Twice")), "another user may own the same name");
  }

  /**
   * Discovery's write refreshes what the server says and the missing mark, and a
   * write addressed to another user's row does nothing at all.
   */
  @Test
  void discoveryRefreshesTheRowAndAnotherUserCannotReachIt() {
    EmailFolder created = emailFolderStorage.createFolder(newFolder("bob", "INBOX.Factures", "Factures"));

    emailFolderStorage.markSeen("bob", created.getId(), "Invoices", ".", new Date(5_000L));
    emailFolderStorage.markMissing("bob", created.getId());
    EmailFolder missing = emailFolderStorage.getFolder("bob", created.getId());
    assertTrue(missing.isMissing());
    assertEquals("Invoices", missing.getDisplayName());
    assertEquals(".", missing.getDelimiter());
    assertEquals(5_000L, missing.getLastSeenDate().getTime());

    emailFolderStorage.markSeen("mallory", created.getId(), "Stolen", "/", new Date());
    emailFolderStorage.updateSyncEnabled("mallory", created.getId(), true, new Date());
    assertNull(emailFolderStorage.getFolder("mallory", created.getId()));
    EmailFolder untouched = emailFolderStorage.getFolder("bob", created.getId());
    assertEquals("Invoices", untouched.getDisplayName());
    assertFalse(untouched.isSyncEnabled(), "another user's opt-in is a no-op");

    emailFolderStorage.deleteFolder("mallory", created.getId());
    assertNotNull(emailFolderStorage.getFolder("bob", created.getId()), "another user's delete is a no-op");
    emailFolderStorage.deleteFolders("bob");
    assertNull(emailFolderStorage.getFolder("bob", created.getId()));
  }

  /**
   * A rename replaces the row's own two name columns and only those -- the opt-in and
   * the sync memory a rename never touches survive it untouched -- and a rename
   * addressed to another user's row does nothing (EXO-89943).
   */
  @Test
  void renameReplacesOnlyTheRowsOwnNameColumnsAndOnlyThisUsersRow() {
    EmailFolder created = emailFolderStorage.createFolder(newFolder("frank", "Factures", "Factures"));
    emailFolderStorage.updateSyncEnabled("frank", created.getId(), true, new Date(1_000L));
    emailFolderStorage.updateSyncMemory("frank", created.getId(), new FolderSyncSnapshot(1L, 2L, 3L, 4L, 50), new Date(2_000L));

    EmailFolder renamed = emailFolderStorage.renameFolder("frank", created.getId(), "Invoices", "Invoices");

    assertEquals(created.getId(), renamed.getId(), "the SAME row -- its CUSTOM:<id> key is unchanged");
    assertEquals("Invoices", renamed.getRemoteName());
    assertEquals("Invoices", renamed.getDisplayName());
    assertTrue(renamed.isSyncEnabled(), "the opt-in a rename never touches survives it");
    assertEquals(new FolderSyncSnapshot(1L, 2L, 3L, 4L, 50), renamed.getSnapshot(), "so does the sync memory");
    assertEquals(1_000L, renamed.getEnabledDate().getTime());
    assertEquals(2_000L, renamed.getLastSyncDate().getTime());

    emailFolderStorage.renameFolder("mallory", created.getId(), "Stolen", "Stolen");
    EmailFolder untouched = emailFolderStorage.getFolder("frank", created.getId());
    assertEquals("Invoices", untouched.getRemoteName(), "another user's rename is a no-op");
  }

  /**
   * A registry DTO ready to be created.
   *
   * @param userId the owner
   * @param remoteName the IMAP full name
   * @param displayName the last segment
   * @return the DTO
   */
  private EmailFolder newFolder(String userId, String remoteName, String displayName) {
    EmailFolder folder = new EmailFolder();
    folder.setUserId(userId);
    folder.setRemoteName(remoteName);
    folder.setDisplayName(displayName);
    folder.setDelimiter("/");
    folder.setType(MailFolderView.TYPE_CUSTOM);
    folder.setDiscoveredDate(new Date());
    folder.setLastSeenDate(new Date());
    return folder;
  }
}
