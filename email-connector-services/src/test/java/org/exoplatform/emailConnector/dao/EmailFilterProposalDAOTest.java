/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import org.exoplatform.emailConnector.entity.EmailFilterMatchEntity;
import org.exoplatform.emailConnector.entity.EmailFilterProposalEntity;

/**
 * The statements the move of the suggestions to the AI add-on's shared proposals rides
 * on (EXO-90956), executed by the engine on in-memory HSQLDB through the real repository
 * proxies: the former store read by pages of every user's rows, by id; the rule and the
 * Message-ID of some of a user's matches, never another user's; the ids of the matches
 * the retention deletes.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop" })
public class EmailFilterProposalDAOTest {

  private static final String    OWNER = "alice";

  private static final String    OTHER = "bob";

  private static final long      NOW   = 1_790_000_000_000L;

  @Autowired
  private TestEntityManager      entityManager;

  @Autowired
  private EmailFilterProposalDAO emailFilterProposalDAO;

  @Autowired
  private EmailFilterMatchDAO    emailFilterMatchDAO;

  /**
   * The minimal Spring slice: the entities and their repositories.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailFilterProposalEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailFilterProposalDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * The former store is read by pages of every user's rows, by id, after the id the
   * previous page ended on.
   */
  @Test
  void theFormerStoreIsReadByPages() {
    Long first = persist(OWNER, 7L, "hash-a");
    Long second = persist(OTHER, 8L, "hash-a");
    Long third = persist(OWNER, 9L, "hash-b");
    entityManager.flush();
    entityManager.clear();

    assertEquals(List.of(first, second), ids(emailFilterProposalDAO.findPage(0L, PageRequest.of(0, 2))));
    assertEquals(List.of(third), ids(emailFilterProposalDAO.findPage(second, PageRequest.of(0, 2))));
    assertTrue(emailFilterProposalDAO.findPage(third, PageRequest.of(0, 2)).isEmpty());
  }

  /**
   * The keys of some matches are their owner's only; the matches older than a date are
   * listed, the newer ones not.
   */
  @Test
  void theMatchKeysAreTheOwnersAndTheOldOnesListed() {
    Long mine = persistMatch(OWNER, "m1", NOW - 10_000);
    Long newer = persistMatch(OWNER, "m2", NOW + 10_000);
    Long others = persistMatch(OTHER, "m3", NOW - 10_000);
    entityManager.flush();
    entityManager.clear();

    Map<Long, String> keys = new HashMap<>();
    emailFilterMatchDAO.findMatchKeys(OWNER, List.of(mine, newer, others)).forEach(row -> keys.put((Long) row[0], row[1] + " " + row[2]));
    assertEquals(Map.of(mine, "3 <m1@x>", newer, "3 <m2@x>"), keys);
    assertEquals(List.of(mine), emailFilterMatchDAO.findIdsOlderThan(OWNER, new Date(NOW)));
    assertTrue(emailFilterMatchDAO.findIdsOlderThan(OWNER, new Date(NOW - 20_000)).isEmpty());
  }

  /**
   * @param proposals rows
   * @return their ids, in order
   */
  private List<Long> ids(List<EmailFilterProposalEntity> proposals) {
    return proposals.stream().map(EmailFilterProposalEntity::getId).toList();
  }

  /**
   * Persists a match.
   *
   * @param userId the owner
   * @param mail the mail's Message-ID local part
   * @param matched when it matched
   * @return its id
   */
  private Long persistMatch(String userId, String mail, long matched) {
    EmailFilterMatchEntity entity = new EmailFilterMatchEntity();
    entity.setUserId(userId);
    entity.setFilterId(3L);
    entity.setMailHeaderId("<" + mail + "@x>");
    entity.setMailHeaderHash(mail);
    entity.setMatchedDate(new Date(matched));
    entity.setAgentStatus("DONE");
    entity.setCreatedDate(new Date(matched));
    return entityManager.persist(entity).getId();
  }

  /**
   * Persists a proposal.
   *
   * @param userId the owner
   * @param matchId the match
   * @param hash the call's hash
   * @return its id
   */
  private Long persist(String userId, long matchId, String hash) {
    EmailFilterProposalEntity entity = new EmailFilterProposalEntity();
    entity.setUserId(userId);
    entity.setMatchId(matchId);
    entity.setFilterId(3L);
    entity.setToolName("create_task_in_project");
    entity.setToolTitle("Create task");
    entity.setArguments("{\"title\":\"Pay\"}");
    entity.setCallHash(hash);
    entity.setStatus("PROPOSED");
    entity.setCreatedDate(new Date(NOW - 5_000));
    entity.setExpiresDate(new Date(NOW + 5_000));
    entity.setConversationId("run-1");
    return entityManager.persist(entity).getId();
  }
}
