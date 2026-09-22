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
   * The wipe drops the rows a user appears on either side of, and nobody else's.
   */
  @Test
  void theWipeDropsBothSidesOfAUser() {
    persist(GRANTEE, OWNER, "alice@acme.com", 7L, "ACCEPTED");
    persist(OWNER, OTHER, "carol@acme.com", 7L, "ACCEPTED");
    persist(GRANTEE, OTHER, "carol@acme.com", 7L, "PENDING");
    entityManager.clear();

    emailDelegationDAO.deleteByUserId(OWNER);

    assertEquals(1, emailDelegationDAO.findAll().size());
    assertEquals(1, emailDelegationDAO.findByGranteeId(GRANTEE).size());
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
