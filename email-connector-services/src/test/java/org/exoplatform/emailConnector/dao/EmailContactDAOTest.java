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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.entity.EmailContactEntity;
import org.exoplatform.emailConnector.model.EmailContactSource;
import org.exoplatform.emailConnector.model.MailFolder;

/**
 * Real-database smoke test of the new queries, on in-memory HSQLDB — the same
 * dialect as the dev rig. It exists because the unit tests mock every DAO, so
 * nothing else ever PARSES the JPQL or runs its SQL shapes: this repo already
 * shipped a query (LOCATE on a CLOB) that every mocked test loved and the
 * first live sync aborted on. Covers the contact queries (LIKE/CONCAT on
 * VARCHAR, the GROUP BY bucket count, the ordering) and the mail-side
 * contact-source projection, whose {@code email.to} path element is an HQL
 * keyword that only a real parser can bless.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop" })
public class EmailContactDAOTest {

  private static final String USERNAME     = "alice";

  private static final Sort   CONTACT_SORT = Sort.by(Sort.Direction.ASC, "sortBucket", "sortName", "id");

  @Autowired
  private TestEntityManager   entityManager;

  @Autowired
  private EmailContactDAO     emailContactDAO;

  @Autowired
  private EmailBoxDAO         emailBoxDAO;

  /**
   * The minimal Spring slice: the two entities and the two repositories, with
   * Boot's auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailContactEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailContactDAO.class)
  static class JpaSliceConfiguration {
  }

  @Test
  void contactQueriesRunOnARealDatabase() {
    persistContact("bob@example.org", "Bob Smith", "SMITH BOB", 18, false, null);
    persistContact("ann@example.org", "Ann Ax", "AX ANN", 0, false, "other,ann@other.org");
    persistContact("gone@example.org", "Gone", "GONE", 6, true, null);
    persistContact("42@example.org", "42 Things", "42 THINGS", 26, false, null);

    // Browse: suppressed rows invisible, order = bucket then sort key.
    Page<EmailContactEntity> page = emailContactDAO.findContacts(USERNAME, null, PageRequest.of(0, 10, CONTACT_SORT));
    assertEquals(List.of("ann@example.org", "bob@example.org", "42@example.org"),
                 page.getContent().stream().map(EmailContactEntity::getPrimaryEmail).toList());

    // Search: LIKE over the lowered columns.
    Page<EmailContactEntity> hits = emailContactDAO.findContacts(USERNAME, "smith", PageRequest.of(0, 10, CONTACT_SORT));
    assertEquals(1, hits.getTotalElements());
    assertEquals("bob@example.org", hits.getContent().get(0).getPrimaryEmail());

    // Source restriction.
    Page<EmailContactEntity> collected = emailContactDAO.findContactsBySources(USERNAME,
                                                                               List.of(EmailContactSource.COLLECTED),
                                                                               null,
                                                                               PageRequest.of(0, 10, CONTACT_SORT));
    assertEquals(3, collected.getTotalElements());

    // The letter-index GROUP BY, in bucket order.
    List<Object[]> buckets = emailContactDAO.countBySortBucket(USERNAME, null);
    assertEquals(3, buckets.size());
    assertEquals(0, ((Number) buckets.get(0)[0]).intValue());
    assertEquals(18, ((Number) buckets.get(1)[0]).intValue());
    assertEquals(26, ((Number) buckets.get(2)[0]).intValue());

    // The secondary-address LIKE, bounded on both sides.
    assertEquals("ann@example.org",
                 emailContactDAO.findBySecondaryEmail(USERNAME, "ann@other.org").get(0).getPrimaryEmail());
    assertTrue(emailContactDAO.findBySecondaryEmail(USERNAME, "ann@other.or").isEmpty());

    // The presence probe counts tombstones too.
    assertEquals(4, emailContactDAO.countByUserIdAndSourceIn(USERNAME, List.of(EmailContactSource.COLLECTED)));

    // The directory import's identity-link lookup.
    EmailContactEntity linked = new EmailContactEntity();
    linked.setUserId(USERNAME);
    linked.setSource(EmailContactSource.DIRECTORY);
    linked.setPrimaryEmail("jdoe@example.org");
    linked.setSortName("JDOE");
    linked.setSortBucket(9);
    linked.setPlatformUsername("jdoe");
    entityManager.persist(linked);
    entityManager.flush();
    assertEquals("jdoe@example.org",
                 emailContactDAO.findFirstByUserIdAndPlatformUsername(USERNAME, "jdoe").orElseThrow().getPrimaryEmail());
    assertTrue(emailContactDAO.findFirstByUserIdAndPlatformUsername(USERNAME, "ghost").isEmpty());
  }

  @Test
  void contactSourceProjectionRunsOnARealDatabase() {
    EmailBoxEntity email = new EmailBoxEntity();
    email.setMailRemoteId(101L);
    email.setUserId(USERNAME);
    email.setFolder(MailFolder.INBOX);
    email.setSender("Bob Smith,bob@example.org");
    email.setTo("Alice,alice@example.com");
    email.setCc("");
    email.setReceivedDate(new java.util.Date());
    entityManager.persist(email);
    entityManager.flush();

    List<Object[]> rows = emailBoxDAO.findContactSourceRowsByUserIdAndFolder(USERNAME, MailFolder.INBOX);
    assertEquals(1, rows.size());
    assertEquals("Bob Smith,bob@example.org", rows.get(0)[0]);
    assertEquals("Alice,alice@example.com", rows.get(0)[1]);

    List<Object[]> byUid = emailBoxDAO.findContactSourceRowsByUserIdAndFolderAndUids(USERNAME,
                                                                                    MailFolder.INBOX,
                                                                                    List.of(101L));
    assertEquals(1, byUid.size());
    assertTrue(emailBoxDAO.findContactSourceRowsByUserIdAndFolderAndUids(USERNAME, MailFolder.INBOX, List.of(999L)).isEmpty());
  }

  /**
   * Persists one contact row with the fields the queries look at.
   *
   * @param address the primary email
   * @param displayName the display name
   * @param sortName the derived sort key
   * @param sortBucket the derived bucket
   * @param suppressed whether the row is a tombstone
   * @param emails the encoded secondary addresses, may be null
   */
  private void persistContact(String address, String displayName, String sortName, int sortBucket, boolean suppressed,
                              String emails) {
    EmailContactEntity entity = new EmailContactEntity();
    entity.setUserId(USERNAME);
    entity.setSource(EmailContactSource.COLLECTED);
    entity.setPrimaryEmail(address);
    entity.setDisplayName(displayName);
    entity.setSortName(sortName);
    entity.setSortBucket(sortBucket);
    entity.setSuppressed(suppressed);
    entity.setEmails(emails);
    entityManager.persist(entity);
    entityManager.flush();
  }
}
