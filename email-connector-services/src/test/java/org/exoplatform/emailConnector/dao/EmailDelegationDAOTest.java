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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import org.exoplatform.emailConnector.entity.EmailDelegationEntity;

import jakarta.persistence.PersistenceException;

/**
 * The delegation rows' queries, executed by the engine on in-memory HSQLDB through the
 * real repository proxy -- a mock suite is green with a statement the engine refuses.
 * What only a real database shows is here too: that every id read is scoped to its
 * viewer, and that the unique key refuses a second subscription of one grantee to one
 * mailbox on one preset.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop" })
public class EmailDelegationDAOTest {

  private static final String OWNER   = "alice";

  private static final String GRANTEE = "bob";

  private static final String OTHER   = "carol";

  @Autowired
  private TestEntityManager   entityManager;

  @Autowired
  private EmailDelegationDAO  emailDelegationDAO;

  /**
   * The minimal Spring slice: the entity and its repository.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailDelegationEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailDelegationDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * An id resolves with its grantee, and with its owner -- and with nobody else.
   */
  @Test
  void anIdResolvesOnlyWithItsViewer() {
    Long id = persist(GRANTEE, OWNER, "alice@acme.com", 7L, "PENDING");
    entityManager.clear();

    assertEquals(1, emailDelegationDAO.findByIdAndGranteeId(id, GRANTEE).size());
    assertEquals(1, emailDelegationDAO.findByIdAndOwnerId(id, OWNER).size());
    assertTrue(emailDelegationDAO.findByIdAndGranteeId(id, OTHER).isEmpty(), "someone else's id, as grantee");
    assertTrue(emailDelegationDAO.findByIdAndOwnerId(id, OTHER).isEmpty(), "someone else's id, as owner");
    assertTrue(emailDelegationDAO.findByIdAndGranteeId(id, OWNER).isEmpty(), "the owner is not the grantee");
  }

  /**
   * The unique key, and its lookup.
   */
  @Test
  void theKeyIsOneGranteeOneMailboxOnePreset() {
    persist(GRANTEE, OWNER, "alice@acme.com", 7L, "PENDING");
    persist(GRANTEE, OWNER, "alice@acme.com", 8L, "PENDING");
    persist(OTHER, OWNER, "alice@acme.com", 7L, "ACCEPTED");
    entityManager.clear();

    assertEquals(1, emailDelegationDAO.findByGranteeIdAndConnectorIdAndOwnerMailbox(GRANTEE, 7L, "alice@acme.com").size());
    assertTrue(emailDelegationDAO.findByGranteeIdAndConnectorIdAndOwnerMailbox(GRANTEE, 9L, "alice@acme.com").isEmpty());
    assertThrows(PersistenceException.class,
                 () -> persist(GRANTEE, OWNER, "alice@acme.com", 7L, "DECLINED"),
                 "a second subscription of the same grantee to the same mailbox on the same preset is refused");
  }

  /**
   * The per-user listings and the count.
   */
  @Test
  void theListingsAreScopedAndTheCountIsByState() {
    persist(GRANTEE, OWNER, "alice@acme.com", 7L, "ACCEPTED");
    persist(GRANTEE, OTHER, "carol@acme.com", 7L, "ACCEPTED");
    persist(GRANTEE, null, "dave@acme.com", 7L, "AVAILABLE");
    persist(OTHER, OWNER, "alice@acme.com", 7L, "PENDING");
    entityManager.clear();

    List<EmailDelegationEntity> received = emailDelegationDAO.findByGranteeId(GRANTEE);
    assertEquals(3, received.size());
    assertTrue(received.stream().allMatch(row -> GRANTEE.equals(row.getGranteeId())));
    List<EmailDelegationEntity> granted = emailDelegationDAO.findByOwnerId(OWNER);
    assertEquals(2, granted.size());
    assertTrue(granted.stream().allMatch(row -> OWNER.equals(row.getOwnerId())));
    assertEquals(2, emailDelegationDAO.countByGranteeIdAndStatus(GRANTEE, "ACCEPTED"));
    assertEquals(0, emailDelegationDAO.countByGranteeIdAndStatus(OTHER, "ACCEPTED"));
  }

  /**
   * #432-2 -- the grantee's toggles are written alone, in SQL: a revoke committed since
   * the grantee's read stays revoked, and only that grantee's row is touched.
   */
  @Test
  void thePreferencesWriteTouchesTheTwoTogglesOnly() {
    Long id = persist(GRANTEE, OWNER, "alice@acme.com", 7L, "ACCEPTED");
    entityManager.clear();
    EmailDelegationEntity revoked = emailDelegationDAO.findById(id).orElseThrow();
    revoked.setStatus("REVOKED");
    revoked.setRevokedDate(new Date(5_000L));
    emailDelegationDAO.saveAndFlush(revoked);
    entityManager.clear();

    assertEquals(1, emailDelegationDAO.updatePreferences(id, GRANTEE, true, true, new Date(6_000L)));
    assertEquals(0, emailDelegationDAO.updatePreferences(id, OTHER, false, false, new Date(7_000L)), "somebody else's row");
    entityManager.clear();

    EmailDelegationEntity read = emailDelegationDAO.findById(id).orElseThrow();
    assertEquals("REVOKED", read.getStatus(), "the owner's revoke stands");
    assertEquals(5_000L, read.getRevokedDate().getTime());
    assertTrue(read.isBadgeIncluded());
    assertTrue(read.isNotifyNewMail());
  }

  /**
   * EXO-90554 -- the search toggle is written alone, in SQL, and on that grantee's row
   * only: a revoke committed since the grantee's read stays revoked, and the badge and
   * notification toggles keep what they held. A new row is searched by default.
   */
  @Test
  void theSearchToggleWriteTouchesThatToggleOnly() {
    Long id = persist(GRANTEE, OWNER, "alice@acme.com", 7L, "ACCEPTED");
    entityManager.clear();
    EmailDelegationEntity revoked = emailDelegationDAO.findById(id).orElseThrow();
    assertTrue(revoked.isSearchIncluded(), "searched by default");
    revoked.setStatus("REVOKED");
    revoked.setBadgeIncluded(true);
    emailDelegationDAO.saveAndFlush(revoked);
    entityManager.clear();

    assertEquals(1, emailDelegationDAO.updateSearchIncluded(id, GRANTEE, false, new Date(6_000L)));
    assertEquals(0, emailDelegationDAO.updateSearchIncluded(id, OTHER, true, new Date(7_000L)), "somebody else's row");
    entityManager.clear();

    EmailDelegationEntity read = emailDelegationDAO.findById(id).orElseThrow();
    assertFalse(read.isSearchIncluded());
    assertEquals("REVOKED", read.getStatus(), "the owner's revoke stands");
    assertTrue(read.isBadgeIncluded(), "the badge toggle is not written");
    assertEquals(6_000L, read.getUpdatedDate().getTime());
  }

  /**
   * EXO-90556 -- the owner's per-folder write, executed: the role folders, the owner's
   * folder of each and the exceptions are written, and nothing else of the row -- the
   * INBOX letters a grantee's pass refreshed meanwhile stay; only that owner's row, and
   * never a share that ended while the server was being asked.
   */
  @Test
  void theFolderGrantsWriteTouchesTheOwnersFolderColumnsOnly() {
    Long id = persist(GRANTEE, OWNER, "alice@acme.com", 7L, "ACCEPTED");
    entityManager.clear();
    EmailDelegationEntity refreshed = emailDelegationDAO.findById(id).orElseThrow();
    refreshed.setRights("lr");
    refreshed.setGrantedRoles("INBOX,SENT,TRASH");
    emailDelegationDAO.saveAndFlush(refreshed);
    entityManager.clear();
    List<String> ended = List.of("REVOKED", "GONE");

    assertEquals(1,
                 emailDelegationDAO.updateFolderGrants(id,
                                                       OWNER,
                                                       "INBOX,SENT",
                                                       "{\"TRASH\":\"Trash\"}",
                                                       "TRASH=NONE,JUNK=READER",
                                                       new Date(6_000L),
                                                       ended));
    assertEquals(0,
                 emailDelegationDAO.updateFolderGrants(id, GRANTEE, "INBOX", null, null, new Date(7_000L), ended),
                 "the grantee is not the owner");
    entityManager.clear();

    EmailDelegationEntity read = emailDelegationDAO.findById(id).orElseThrow();
    assertEquals("INBOX,SENT", read.getGrantedRoles());
    assertEquals("{\"TRASH\":\"Trash\"}", read.getOwnerRoleFolders());
    assertEquals("TRASH=NONE,JUNK=READER", read.getFolderAccess());
    assertEquals("lr", read.getRights(), "the INBOX letters are not written");
    assertEquals("ACCEPTED", read.getStatus());
    assertEquals(6_000L, read.getUpdatedDate().getTime());

    read.setStatus("REVOKED");
    emailDelegationDAO.saveAndFlush(read);
    entityManager.clear();
    assertEquals(0,
                 emailDelegationDAO.updateFolderGrants(id, OWNER, "INBOX", null, null, new Date(8_000L), ended),
                 "a share revoked meanwhile is not written");
    assertEquals("TRASH=NONE,JUNK=READER", emailDelegationDAO.findById(id).orElseThrow().getFolderAccess());
  }

  /**
   * The sync tier, executed: only ACCEPTED rows of that grantee whose activity stamp is
   * at or after the threshold. A row never opened (null stamp) is never active, which is
   * what makes an accepted-and-forgotten share cost nothing; a PENDING row is never
   * active whatever its stamp; and another grantee's active row is not in the answer.
   */
  @Test
  void onlyAcceptedRowsStampedSinceTheThresholdAreActive() {
    Date now = new Date();
    Date activeSince = new Date(now.getTime() - 60_000L);
    Long inTheMailbox = persist(GRANTEE, OWNER, "alice@acme.com", 7L, "ACCEPTED");
    stamp(inTheMailbox, now);
    Long longAgo = persist(GRANTEE, OTHER, "carol@acme.com", 7L, "ACCEPTED");
    stamp(longAgo, new Date(now.getTime() - 600_000L));
    persist(GRANTEE, "dave", "dave@acme.com", 7L, "ACCEPTED");
    Long notYetAccepted = persist(GRANTEE, "erin", "erin@acme.com", 7L, "PENDING");
    stamp(notYetAccepted, now);
    Long someoneElses = persist(OTHER, OWNER, "alice@acme.com", 7L, "ACCEPTED");
    stamp(someoneElses, now);
    entityManager.clear();

    List<EmailDelegationEntity> active = emailDelegationDAO.findActiveByGranteeId(GRANTEE, "ACCEPTED", activeSince);

    assertEquals(1, active.size(), "the one accepted share this grantee is actually in");
    assertEquals(inTheMailbox, active.get(0).getId());
  }

  /**
   * The activity stamp, executed: written when the previous one is older than the
   * throttle (or absent), a no-op when it is fresher, and never on somebody else's row.
   * The throttle is in the WHERE clause on purpose -- a map would be per JVM, and this
   * has to hold across the cluster.
   */
  @Test
  void theActivityStampIsThrottledInTheStatementAndScopedToItsGrantee() {
    Date now = new Date();
    Long id = persist(GRANTEE, OWNER, "alice@acme.com", 7L, "ACCEPTED");
    entityManager.clear();

    assertEquals(1, emailDelegationDAO.touchActivity(id, GRANTEE, now, new Date(now.getTime() - 600_000L)),
                 "a row with no stamp at all is stamped");
    entityManager.clear();
    assertEquals(0, emailDelegationDAO.touchActivity(id, GRANTEE, new Date(now.getTime() + 1_000L), new Date(now.getTime() - 600_000L)),
                 "a stamp fresher than the throttle is left alone");
    entityManager.clear();
    assertEquals(0, emailDelegationDAO.touchActivity(id, OTHER, new Date(now.getTime() + 600_000L), now),
                 "somebody else cannot stamp this share");
    entityManager.clear();
    assertEquals(1, emailDelegationDAO.touchActivity(id, GRANTEE, new Date(now.getTime() + 600_000L), new Date(now.getTime() + 1_000L)),
                 "a stamp older than the throttle is rewritten");
    entityManager.clear();
    assertEquals(new Date(now.getTime() + 600_000L).getTime(),
                 emailDelegationDAO.findById(id).orElseThrow().getLastActivityDate().getTime());
  }

  /**
   * Stamps one row's activity directly, to set up the tier tests.
   *
   * @param id the row id
   * @param when the stamp
   */
  private void stamp(Long id, Date when) {
    EmailDelegationEntity entity = entityManager.find(EmailDelegationEntity.class, id);
    entity.setLastActivityDate(when);
    entityManager.persistAndFlush(entity);
  }

  /**
   * Persists one row.
   *
   * @param granteeId the grantee
   * @param ownerId the owner, possibly null
   * @param ownerMailbox the owner's identifier
   * @param connectorId the preset
   * @param status the state
   * @return the id
   */
  private Long persist(String granteeId, String ownerId, String ownerMailbox, long connectorId, String status) {
    EmailDelegationEntity entity = new EmailDelegationEntity();
    entity.setGranteeId(granteeId);
    entity.setOwnerId(ownerId);
    entity.setOwnerMailbox(ownerMailbox);
    entity.setConnectorId(connectorId);
    entity.setPreset("READER");
    entity.setRights("lrs");
    entity.setStatus(status);
    entity.setOrigin("EXO");
    entity.setCreatedDate(new Date());
    entity.setUpdatedDate(new Date());
    return entityManager.persistAndFlush(entity).getId();
  }
}
