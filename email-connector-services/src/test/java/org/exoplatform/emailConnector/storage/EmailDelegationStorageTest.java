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
 * EXO-90548 -- what an owner's grant covered, on a delegation row, over the schema the
 * shipped changelog builds (1.0.0-82 included): the granted roles and the owner's
 * role-to-folder-name map round-trip, and a map this version cannot read is read as none.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailDelegationStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailDelegationStorageTest {

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
   * The granted roles and the owner's role folders are stored and read back; an update
   * keeps them.
   */
  @Test
  void whatAGrantCoveredRoundTrips() {
    EmailDelegation row = newRow("bob", "alice@acme.com");
    row.setGrantedRoles("SENT,ARCHIVE,TRASH,JUNK");
    Map<FolderRole, String> roleFolders = new EnumMap<>(FolderRole.class);
    roleFolders.put(FolderRole.TRASH, "Corbeille");
    roleFolders.put(FolderRole.SENT, "Éléments envoyés");
    row.setOwnerRoleFolders(roleFolders);

    EmailDelegation created = emailDelegationStorage.create(row);
    EmailDelegation read = emailDelegationStorage.getAsGrantee("bob", created.getId());

    assertEquals("SENT,ARCHIVE,TRASH,JUNK", read.getGrantedRoles());
    assertEquals(roleFolders, read.getOwnerRoleFolders());
    read.setStatus(DelegationStatus.ACCEPTED);
    emailDelegationStorage.update(read);
    assertEquals(roleFolders, emailDelegationStorage.getAsGrantee("bob", created.getId()).getOwnerRoleFolders(),
                 "an update keeps the map");
  }

  /**
   * A row written before the grant recorded anything reads as an INBOX-only share with no
   * map; a stored map that cannot be read, or that names a role this version does not
   * know, reads as what can be read -- never as an error that would break the listing.
   */
  @Test
  void aRowWithoutOrWithAnUnreadableMapReadsAsNone() {
    EmailDelegation legacy = emailDelegationStorage.create(newRow("carol", "alice@acme.com"));
    EmailDelegation readLegacy = emailDelegationStorage.getAsGrantee("carol", legacy.getId());
    assertNull(readLegacy.getGrantedRoles());
    assertTrue(readLegacy.getOwnerRoleFolders().isEmpty());

    EmailDelegation broken = emailDelegationStorage.create(newRow("dave", "alice@acme.com"));
    EmailDelegationEntity entity = emailDelegationDAO.findById(broken.getId()).orElseThrow();
    entity.setOwnerRoleFolders("{not json");
    emailDelegationDAO.saveAndFlush(entity);
    assertTrue(emailDelegationStorage.getAsGrantee("dave", broken.getId()).getOwnerRoleFolders().isEmpty());

    entity.setOwnerRoleFolders("{\"CALENDAR\":\"Calendar\",\"TRASH\":\"Trash\",\"SENT\":7}");
    emailDelegationDAO.saveAndFlush(entity);
    assertEquals(Map.of(FolderRole.TRASH, "Trash"), emailDelegationStorage.getAsGrantee("dave", broken.getId()).getOwnerRoleFolders());
  }

  /**
   * A fresh row of a grantee on one mailbox.
   *
   * @param granteeId the grantee
   * @param ownerMailbox the owner's mailbox
   * @return the row, not yet created
   */
  private EmailDelegation newRow(String granteeId, String ownerMailbox) {
    EmailDelegation row = new EmailDelegation();
    row.setGranteeId(granteeId);
    row.setOwnerId("alice");
    row.setOwnerMailbox(ownerMailbox);
    row.setConnectorId(7L);
    row.setPreset(DelegationPreset.READER);
    row.setRights("lrs");
    row.setStatus(DelegationStatus.PENDING);
    row.setOrigin(DelegationOrigin.EXO);
    row.setInvitedDate(new Date());
    return row;
  }
}
