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
import org.exoplatform.emailConnector.model.SendMode;

/**
 * Stack review #432-2 -- what a whole-row write may not put back, in SQL over the shipped
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
   * Stack review N-1, in SQL -- the owner's change of access and a grantee's leave
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
   * The per-folder write (EXO-90548 on N-1), in SQL: the rights and the folder roles
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
   * EXO-90548 review, finding 1, in SQL: the owner extends a pending share while its
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
   * EXO-90548 review, finding 1, in SQL: the owner's listing refreshes a share on offer
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
   * EXO-90582 -- the owner's consent to writing in her name, over the shipped changelog:
   * written by its own statement on the owner's live share, with its date; withdrawn to
   * no mode and no date; refused on another owner's id and on an ended share.
   */
  @Test
  void theSendModeIsWrittenAloneOnTheOwnersLiveShare() {
    EmailDelegation row = emailDelegationStorage.create(acceptedRow("jack"));

    EmailDelegation consented = emailDelegationStorage.updateSendMode("alice", row.getId(), SendMode.AS);
    assertEquals(SendMode.AS, consented.getSendMode());
    assertNotNull(consented.getSendModeDate());
    assertNull(consented.getSendRefusedDate());
    assertEquals(DelegationStatus.ACCEPTED, consented.getStatus());
    assertNull(emailDelegationStorage.updateSendMode("bob", row.getId(), SendMode.ON_BEHALF), "another owner's id writes nothing");

    EmailDelegation withdrawn = emailDelegationStorage.updateSendMode("alice", row.getId(), SendMode.NONE);
    assertNull(withdrawn.getSendMode(), "a withdrawal stores no mode");
    assertNull(withdrawn.getSendModeDate(), "and no date: the row's shape before the feature");

    EmailDelegationEntity ended = emailDelegationDAO.findById(row.getId()).orElseThrow();
    ended.setStatus(DelegationStatus.REVOKED.name());
    emailDelegationDAO.saveAndFlush(ended);
    assertNull(emailDelegationStorage.updateSendMode("alice", row.getId(), SendMode.ON_BEHALF), "an ended share is not written");
  }

  /**
   * EXO-90582 -- a whole-row write from a DTO read before the owner set, or withdrew, her
   * consent neither undoes nor revives it: the consent's columns are never in that write.
   * The stale DTO's own change lands.
   */
  @Test
  void aStaleWholeRowWriteNeitherUndoesNorRevivesTheSendMode() {
    EmailDelegation readBeforeTheConsent = emailDelegationStorage.create(acceptedRow("kate"));
    emailDelegationStorage.updateSendMode("alice", readBeforeTheConsent.getId(), SendMode.ON_BEHALF);
    readBeforeTheConsent.setRights("lrsw");
    emailDelegationStorage.update(readBeforeTheConsent);
    EmailDelegation read = emailDelegationStorage.getAsOwner("alice", readBeforeTheConsent.getId());
    assertEquals("lrsw", read.getRights(), "the write itself landed");
    assertEquals(SendMode.ON_BEHALF, read.getSendMode(), "and did not undo the consent given meanwhile");

    EmailDelegation readBeforeTheWithdrawal = emailDelegationStorage.getAsOwner("alice", readBeforeTheConsent.getId());
    emailDelegationStorage.updateSendMode("alice", readBeforeTheWithdrawal.getId(), SendMode.NONE);
    readBeforeTheWithdrawal.setRights("lrs");
    emailDelegationStorage.update(readBeforeTheWithdrawal);
    assertNull(emailDelegationStorage.getAsOwner("alice", readBeforeTheWithdrawal.getId()).getSendMode(),
               "nor revive the consent withdrawn meanwhile");
  }

  /**
   * EXO-90626 -- a refusal by the owner's mail server, over the shipped changelog (1.0.0-90
   * included): recorded with its shape on the grantee's row under the consent it was sent
   * under, read back with that shape by the grantee and by the owner; a shape that is
   * neither on behalf nor as the owner is recorded as on behalf, which blocks both; and
   * the owner's next consent clears the date and the shape.
   */
  @Test
  void aSendRefusalIsReadBackWithItsShape() {
    EmailDelegation row = emailDelegationStorage.create(acceptedRow("mia"));
    EmailDelegation consented = emailDelegationStorage.updateSendMode("alice", row.getId(), SendMode.AS);

    assertTrue(emailDelegationStorage.markSendRefused("mia", row.getId(), consented.getSendModeDate(), SendMode.AS));
    EmailDelegation asGrantee = emailDelegationStorage.getAsGrantee("mia", row.getId());
    assertNotNull(asGrantee.getSendRefusedDate());
    assertEquals(SendMode.AS, asGrantee.getSendRefusedMode(), "the grantee reads the shape refused");
    assertEquals(SendMode.AS, emailDelegationStorage.getAsOwner("alice", row.getId()).getSendRefusedMode(), "and so does the owner");
    assertFalse(emailDelegationStorage.markSendRefused("mia", row.getId(), null, SendMode.AS), "a consent with no date is never marked");

    EmailDelegation again = emailDelegationStorage.updateSendMode("alice", row.getId(), SendMode.AS);
    assertNull(again.getSendRefusedDate(), "a consent set again clears the refusal");
    assertNull(again.getSendRefusedMode(), "and its shape");

    assertTrue(emailDelegationStorage.markSendRefused("mia", row.getId(), again.getSendModeDate(), null));
    assertEquals(SendMode.ON_BEHALF, emailDelegationStorage.getAsGrantee("mia", row.getId()).getSendRefusedMode(),
                 "a refusal of no known shape is recorded as on behalf, which blocks both");
  }

  /**
   * EXO-90582 -- the consent taken off a share once it ended, over the shipped changelog:
   * nothing on a live share, all three columns on an ended one.
   */
  @Test
  void theSendModeIsTakenOffAnEndedShareOnly() {
    EmailDelegation row = emailDelegationStorage.create(acceptedRow("liam"));
    emailDelegationStorage.updateSendMode("alice", row.getId(), SendMode.AS);
    assertFalse(emailDelegationStorage.clearSendModeIfEnded(row.getId()), "a live share keeps it");

    EmailDelegationEntity ended = emailDelegationDAO.findById(row.getId()).orElseThrow();
    ended.setStatus(DelegationStatus.DECLINED.name());
    emailDelegationDAO.saveAndFlush(ended);
    assertTrue(emailDelegationStorage.clearSendModeIfEnded(row.getId()));
    EmailDelegation read = emailDelegationStorage.getAsOwner("alice", row.getId());
    assertNull(read.getSendMode());
    assertNull(read.getSendModeDate());
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
