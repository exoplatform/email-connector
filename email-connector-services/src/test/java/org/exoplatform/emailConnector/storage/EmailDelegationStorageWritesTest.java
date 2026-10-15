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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.dao.EmailDelegationDAO;
import org.exoplatform.emailConnector.entity.EmailDelegationEntity;
import org.exoplatform.emailConnector.model.DelegationOrigin;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.DelegationStatus;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.model.FolderRole;

/**
 * What a whole-row write may not put back, in SQL over the shipped
 * changelog: the activity stamp is the grantee's listing's own, written outside any DTO,
 * and a DTO read before that listing must not rewind it.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailDelegationStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EmailDelegationStorageWritesTest {

  @Autowired
  private EmailDelegationStorage emailDelegationStorage;

  @Autowired
  private EmailDelegationDAO     emailDelegationDAO;

  /**
   * The minimal Spring slice: the delegation entity and its repository, migrated by the
   * shipped changelog.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailDelegationEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailDelegationDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * A row read, the grantee lists the mailbox meanwhile, the stale DTO is written back:
   * the listing's stamp stands.
   */
  @Test
  void aStaleWriteNeverRewindsTheActivityStamp() {
    EmailDelegation row = new EmailDelegation();
    row.setGranteeId("bob");
    row.setOwnerId("alice");
    row.setOwnerMailbox("alice@acme.com");
    row.setConnectorId(7L);
    row.setPreset(DelegationPreset.READER);
    row.setRights("lrs");
    row.setStatus(DelegationStatus.ACCEPTED);
    row.setOrigin(DelegationOrigin.EXO);
    EmailDelegation stale = emailDelegationStorage.create(row);

    emailDelegationDAO.touchActivity(stale.getId(), "bob", new Date(9_000L), new Date(10_000L));
    stale.setRights("lrsw");
    emailDelegationStorage.update(stale);

    EmailDelegation read = emailDelegationStorage.getAsGrantee("bob", stale.getId());
    assertEquals(new Date(9_000L), read.getLastActivityDate() == null ? null : new Date(read.getLastActivityDate().getTime()),
                 "the listing's stamp stands");
    assertEquals("lrsw", read.getRights(), "and the write itself landed");
  }

  /**
   * In SQL: the owner's change of access and a grantee's leave
   * committed while the SETACL was on the wire: the rights land, the leave stands
   * (status, badge, response date untouched).
   */
  @Test
  void aChangeOfAccessNeverUndoesALeaveMadeMeanwhile() {
    EmailDelegation stale = emailDelegationStorage.create(acceptedRow("carol"));
    EmailDelegationEntity leave = emailDelegationDAO.findById(stale.getId()).orElseThrow();
    leave.setStatus(DelegationStatus.DECLINED.name());
    leave.setBadgeIncluded(false);
    leave.setRespondedDate(new Date(8_000L));
    emailDelegationDAO.saveAndFlush(leave);

    EmailDelegation changed = emailDelegationStorage.updateGrantedRights("alice",
                                                                         stale.getId(),
                                                                         DelegationPreset.EDITOR,
                                                                         "lrswit",
                                                                         "lrswite",
                                                                         "carol@acme.com",
                                                                         new Date(9_000L));

    assertEquals(DelegationStatus.DECLINED, changed.getStatus(), "the leave stands");
    assertFalse(changed.isBadgeIncluded(), "and so does its badge reset");
    assertEquals(8_000L, changed.getRespondedDate().getTime());
    assertEquals(DelegationPreset.EDITOR, changed.getPreset());
    assertEquals("lrswit", changed.getRights(), "the rights the server holds landed");
    assertEquals("carol@acme.com", changed.getGranteeMailbox());
    assertNull(emailDelegationStorage.updateGrantedRights("bob", stale.getId(), DelegationPreset.READER, "lrs", null, null, new Date()),
               "another owner's id writes nothing");
    assertEquals("lrswit", emailDelegationStorage.getAsOwner("alice", stale.getId()).getRights());
  }

  /**
   * A share revoked (or gone) while the SETACL was on the wire is not written: the
   * answer is null and the row keeps what the revoke left.
   */
  @Test
  void aChangeOfAccessNeverWritesAShareThatEndedMeanwhile() {
    for (DelegationStatus ended : new DelegationStatus[] { DelegationStatus.REVOKED, DelegationStatus.GONE }) {
      EmailDelegation stale = emailDelegationStorage.create(acceptedRow("dave-" + ended.name().toLowerCase()));
      EmailDelegationEntity revoked = emailDelegationDAO.findById(stale.getId()).orElseThrow();
      revoked.setStatus(ended.name());
      emailDelegationDAO.saveAndFlush(revoked);

      assertNull(emailDelegationStorage.updateGrantedRights("alice",
                                                            stale.getId(),
                                                            DelegationPreset.EDITOR,
                                                            "lrswit",
                                                            null,
                                                            null,
                                                            new Date()),
                 ended.name());
      EmailDelegation read = emailDelegationStorage.getAsOwner("alice", stale.getId());
      assertEquals(ended, read.getStatus());
      assertEquals("lrs", read.getRights(), ended.name() + ": nothing written");
    }
  }

  /**
   * The per-folder write, in SQL: the rights and the folder roles
   * land together, a leave made meanwhile stands, another owner's id writes nothing, and
   * a share that ended meanwhile is not written at all.
   */
  @Test
  void aChangeOfFolderAccessWritesTheRolesAndNeverUndoesALeave() {
    EmailDelegation stale = emailDelegationStorage.create(acceptedRow("erin"));
    EmailDelegationEntity leave = emailDelegationDAO.findById(stale.getId()).orElseThrow();
    leave.setStatus(DelegationStatus.DECLINED.name());
    leave.setBadgeIncluded(false);
    emailDelegationDAO.saveAndFlush(leave);
    Map<FolderRole, String> folders = new EnumMap<>(FolderRole.class);
    folders.put(FolderRole.SENT, "Sent");
    folders.put(FolderRole.TRASH, "Corbeille");

    EmailDelegation changed = emailDelegationStorage.updateGrantedRights("alice",
                                                                         stale.getId(),
                                                                         DelegationPreset.EDITOR,
                                                                         "lrswite",
                                                                         null,
                                                                         "erin@acme.com",
                                                                         new Date(),
                                                                         "INBOX,SENT,TRASH",
                                                                         folders);

    assertEquals(DelegationStatus.DECLINED, changed.getStatus(), "the leave stands");
    assertFalse(changed.isBadgeIncluded());
    assertEquals("lrswite", changed.getRights());
    assertEquals("INBOX,SENT,TRASH", changed.getGrantedRoles());
    assertEquals("Corbeille", changed.getOwnerRoleFolders().get(FolderRole.TRASH));
    assertNull(emailDelegationStorage.updateGrantedRights("bob", stale.getId(), DelegationPreset.READER, "lrs", null, null,
                                                          new Date(), "INBOX", folders),
               "another owner's id writes nothing");

    EmailDelegationEntity revoked = emailDelegationDAO.findById(stale.getId()).orElseThrow();
    revoked.setStatus(DelegationStatus.REVOKED.name());
    emailDelegationDAO.saveAndFlush(revoked);
    assertNull(emailDelegationStorage.updateGrantedRights("alice", stale.getId(), DelegationPreset.READER, "lrs", null, null,
                                                          new Date(), "INBOX", folders),
               "a revoked share is not written");
    EmailDelegation read = emailDelegationStorage.getAsOwner("alice", stale.getId());
    assertEquals("INBOX,SENT,TRASH", read.getGrantedRoles(), "nothing written after the revoke");
    assertEquals("lrswite", read.getRights());
  }

  /**
   * In SQL: the owner extends a pending share while its
   * grantee's accept is on the wire. The accept writes its own columns only, so the
   * folder roles the Extend recorded stand; the server's words recorded by the owner's
   * side are kept, and a preset not given is kept too. An accept of a row revoked
   * meanwhile writes nothing.
   */
  @Test
  void anAcceptNeverUndoesAnExtendMadeMeanwhile() {
    EmailDelegation row = acceptedRow("frank");
    row.setStatus(DelegationStatus.PENDING);
    row.setNativeRights("Read, Write");
    EmailDelegation pending = emailDelegationStorage.create(row);
    Map<FolderRole, String> folders = new EnumMap<>(FolderRole.class);
    folders.put(FolderRole.SENT, "Sent");
    assertNotNull(emailDelegationStorage.updateGrantedRights("alice", pending.getId(), DelegationPreset.READER, "lrs", "Read, Write",
                                                             "frank@acme.com", new Date(), "INBOX,SENT", folders));

    EmailDelegation accepted = emailDelegationStorage.accept("frank", pending.getId(), "Other Users/alice", "lrs", null, new Date(9_000L));

    assertEquals(DelegationStatus.ACCEPTED, accepted.getStatus());
    assertEquals("Other Users/alice", accepted.getRemoteRoot());
    assertEquals("INBOX,SENT", accepted.getGrantedRoles(), "the Extend's roles stand");
    assertEquals("Sent", accepted.getOwnerRoleFolders().get(FolderRole.SENT));
    assertEquals("Read, Write", accepted.getNativeRights(), "the owner side's words are kept");
    assertEquals(DelegationPreset.READER, accepted.getPreset(), "no preset given, none written");
    assertNull(emailDelegationStorage.accept("frank", pending.getId(), "x", "lrs", null, new Date()), "already accepted: nothing written");
    assertNull(emailDelegationStorage.accept("bob", pending.getId(), "x", "lrs", null, new Date()), "another grantee's id writes nothing");

    EmailDelegation other = emailDelegationStorage.create(acceptedRow("gina"));
    EmailDelegationEntity revoked = emailDelegationDAO.findById(other.getId()).orElseThrow();
    revoked.setStatus(DelegationStatus.REVOKED.name());
    emailDelegationDAO.saveAndFlush(revoked);
    assertNull(emailDelegationStorage.accept("gina", other.getId(), "x", "lrs", null, new Date()), "a revoked share is not accepted");
    assertEquals(DelegationStatus.REVOKED, emailDelegationStorage.getAsOwner("alice", other.getId()).getStatus());
  }

  /**
   * In SQL: the owner's listing refreshes a share on offer
   * to the letters its ACL holds, without touching the folder roles an Extend wrote, and
   * never writes a share in use.
   */
  @Test
  void theOwnersRefreshOfAShareOnOfferKeepsItsFolderRoles() {
    EmailDelegation row = acceptedRow("hank");
    row.setStatus(DelegationStatus.PENDING);
    EmailDelegation pending = emailDelegationStorage.create(row);
    Map<FolderRole, String> folders = new EnumMap<>(FolderRole.class);
    folders.put(FolderRole.TRASH, "Corbeille");
    emailDelegationStorage.updateGrantedRights("alice", pending.getId(), DelegationPreset.EDITOR, "lrswite", null, "hank@acme.com",
                                               new Date(), "INBOX,TRASH", folders);

    EmailDelegation refreshed = emailDelegationStorage.updateOfferedRights("alice", pending.getId(), "lrswit", "lrswit");

    assertEquals("lrswit", refreshed.getRights());
    assertEquals("INBOX,TRASH", refreshed.getGrantedRoles(), "the Extend's roles stand");
    assertEquals(DelegationStatus.PENDING, refreshed.getStatus());
    EmailDelegation inUse = emailDelegationStorage.create(acceptedRow("ivan"));
    assertNull(emailDelegationStorage.updateOfferedRights("alice", inUse.getId(), "l", "l"), "a share in use is not written");
    assertEquals("lrs", emailDelegationStorage.getAsOwner("alice", inUse.getId()).getRights());
  }

  /**
   * The grantee's identifier is written alone, once, and only where none is recorded:
   * a row that has one keeps it, and another owner's row is never reached.
   */
  @Test
  void theGranteesIdentifierIsRecordedOnceAndOnlyWhereNoneIs() {
    EmailDelegation unnamed = acceptedRow("ned");
    unnamed.setGranteeMailbox(null);
    unnamed = emailDelegationStorage.create(unnamed);

    EmailDelegation named = emailDelegationStorage.updateGranteeMailbox("alice", unnamed.getId(), "ned@acme.com");

    assertEquals("ned@acme.com", named.getGranteeMailbox());
    assertEquals(DelegationStatus.ACCEPTED, named.getStatus(), "nothing else of the row moves");
    assertEquals("lrs", named.getRights());
    assertNull(emailDelegationStorage.updateGranteeMailbox("alice", unnamed.getId(), "someone@else.org"), "written once");
    assertEquals("ned@acme.com", emailDelegationStorage.getAsOwner("alice", unnamed.getId()).getGranteeMailbox());
    assertNull(emailDelegationStorage.updateGranteeMailbox("mallory", unnamed.getId(), "mallory@acme.com"), "the owner's row only");
  }

  /**
   * A share found gone is written alone, in SQL: the status, its instant and the badge
   * opt-out land, and the grantee's notification choice made meanwhile stands.
   */
  @Test
  void anEndedShareNeverUndoesTheGranteesChoiceMadeMeanwhile() {
    EmailDelegation stale = emailDelegationStorage.create(acceptedRow("olga"));
    emailDelegationStorage.updatePreferences("olga", stale.getId(), true, true);

    EmailDelegation ended = emailDelegationStorage.markRevoked(stale.getId(), DelegationStatus.REVOKED);

    assertEquals(DelegationStatus.REVOKED, ended.getStatus());
    assertNotNull(ended.getRevokedDate());
    assertFalse(ended.isBadgeIncluded(), "a share taken up again must be chosen again to count");
    assertTrue(ended.isNotifyNewMail(), "the grantee's choice stands");
  }

  /**
   * A leave is written alone, in SQL: the status, the answer's instant and the badge
   * opt-out land, and the owner's change of access made meanwhile stands.
   */
  @Test
  void aLeaveNeverUndoesAChangeOfAccessMadeMeanwhile() {
    EmailDelegation stale = emailDelegationStorage.create(acceptedRow("pete"));
    emailDelegationStorage.updateGrantedRights("alice",
                                               stale.getId(),
                                               DelegationPreset.EDITOR,
                                               "lrswit",
                                               "lrswite",
                                               "pete@acme.com",
                                               new Date(9_000L));

    EmailDelegation left = emailDelegationStorage.endShare("pete", stale.getId(), DelegationStatus.DECLINED);

    assertEquals(DelegationStatus.DECLINED, left.getStatus());
    assertNotNull(left.getRespondedDate());
    assertFalse(left.isBadgeIncluded());
    assertEquals("lrswit", left.getRights(), "the owner's change of access stands");
    assertEquals(DelegationPreset.EDITOR, left.getPreset());
  }

  /**
   * A leave of a share no longer in use writes nothing, in SQL: a revoke committed first
   * stands.
   */
  @Test
  void aLeaveNeverRevivesARevokedShare() {
    EmailDelegation stale = emailDelegationStorage.create(acceptedRow("tara"));
    emailDelegationStorage.markRevoked(stale.getId(), DelegationStatus.REVOKED);

    EmailDelegation left = emailDelegationStorage.endShare("tara", stale.getId(), DelegationStatus.DECLINED);

    assertEquals(DelegationStatus.REVOKED, left.getStatus(), "the revoke stands");
    assertNull(left.getRespondedDate());
  }

  /**
   * A share offered again is written alone, in SQL: back to AVAILABLE with its revocation
   * cleared and its rights check stamped, the grantee's notification choice untouched; a
   * share in use is never reopened.
   */
  @Test
  void aShareOfferedAgainClearsItsRevocationAlone() {
    EmailDelegation row = emailDelegationStorage.create(acceptedRow("ursula"));
    emailDelegationStorage.updatePreferences("ursula", row.getId(), false, true);
    emailDelegationStorage.markRevoked(row.getId(), DelegationStatus.GONE);

    EmailDelegation reopened = emailDelegationStorage.reopen(row.getId());

    assertEquals(DelegationStatus.AVAILABLE, reopened.getStatus());
    assertNull(reopened.getRevokedDate());
    assertNotNull(reopened.getLastRightsCheckDate());
    assertTrue(reopened.isNotifyNewMail());

    EmailDelegation inUse = emailDelegationStorage.create(acceptedRow("vera"));
    assertEquals(DelegationStatus.ACCEPTED,
                 emailDelegationStorage.reopen(inUse.getId()).getStatus(),
                 "a share in use is not reopened");
  }

  /**
   * An accepted Reader share of alice's mailbox for the given grantee.
   *
   * @param grantee the grantee's username
   * @return the unsaved row
   */
  private EmailDelegation acceptedRow(String grantee) {
    EmailDelegation row = new EmailDelegation();
    row.setGranteeId(grantee);
    row.setOwnerId("alice");
    row.setOwnerMailbox("alice@acme.com");
    row.setConnectorId(7L);
    row.setPreset(DelegationPreset.READER);
    row.setRights("lrs");
    row.setStatus(DelegationStatus.ACCEPTED);
    row.setOrigin(DelegationOrigin.EXO);
    row.setBadgeIncluded(true);
    return row;
  }
}
