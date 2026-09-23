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

import java.util.Date;

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
}
