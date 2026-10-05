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
package org.exoplatform.emailConnector.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
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

import org.exoplatform.emailConnector.dao.EmailFilterProposalDAO;
import org.exoplatform.emailConnector.entity.EmailFilterProposalEntity;
import org.exoplatform.emailConnector.model.EmailFilterProposal;
import org.exoplatform.emailConnector.model.StoredFilterProposal;

/**
 * The former store of the suggestions, read once more by their move to the AI add-on's
 * shared proposals (EXO-90956), on a real engine through the real repository proxy:
 * every column mapped, the owner kept beside each suggestion, pages by id.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = { "spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop" })
public class EmailFilterProposalStorageTest {

  private static final long          NOW = 1_790_000_000_000L;

  @Autowired
  private EmailFilterProposalStorage emailFilterProposalStorage;

  @Autowired
  private EmailFilterProposalDAO     emailFilterProposalDAO;

  /**
   * The minimal Spring slice: the entity, its repository and the storage.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailFilterProposalEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailFilterProposalDAO.class)
  @Import(EmailFilterProposalStorage.class)
  static class JpaSliceConfiguration {
  }

  /** Emptied after each test, every write committed. */
  @AfterEach
  void cleanUp() {
    emailFilterProposalDAO.deleteAll();
  }

  /**
   * Every column is read as stored, the owner beside it; pages follow the ids.
   */
  @Test
  void thePagesReadEveryColumnAndTheOwner() {
    EmailFilterProposalEntity decided = entity("alice", 7L);
    decided.setStatus("EXPIRED");
    decided.setDecidedDate(new Date(NOW + 1));
    decided.setRationale("The mail asks for it");
    decided.setResult("{\"ok\":true}");
    decided.setLastError("emailConnector.filters.proposal.superseded");
    long first = emailFilterProposalDAO.saveAndFlush(decided).getId();
    long second = emailFilterProposalDAO.saveAndFlush(entity("bob", 8L)).getId();

    List<StoredFilterProposal> page = emailFilterProposalStorage.getPage(0, 1);
    assertEquals(1, page.size());
    assertEquals("alice", page.get(0).userId());
    EmailFilterProposal proposal = page.get(0).proposal();
    assertEquals(first, proposal.getId());
    assertEquals(7L, proposal.getMatchId());
    assertEquals(3L, proposal.getFilterId());
    assertEquals("create_task_in_project", proposal.getToolName());
    assertEquals("Create task", proposal.getToolTitle());
    assertEquals("Creates a task", proposal.getToolDescription());
    assertEquals("{\"title\":\"Pay\"}", proposal.getArguments());
    assertEquals("The mail asks for it", proposal.getRationale());
    assertEquals("EXPIRED", proposal.getStatus());
    assertEquals(NOW, proposal.getCreatedDate());
    assertEquals(NOW + 5_000, proposal.getExpiresDate());
    assertEquals(NOW + 1, proposal.getDecidedDate());
    assertEquals("run-1", proposal.getConversationId());
    assertEquals("{\"ok\":true}", proposal.getResult());
    assertEquals("emailConnector.filters.proposal.superseded", proposal.getLastError());
    assertEquals(List.of(second), emailFilterProposalStorage.getPage(first, 10).stream().map(row -> row.proposal().getId()).toList());
    assertTrue(emailFilterProposalStorage.getPage(second, 10).isEmpty());
  }

  /**
   * @param userId the owner
   * @param matchId the match
   * @return a waiting proposal, not stored
   */
  private EmailFilterProposalEntity entity(String userId, long matchId) {
    EmailFilterProposalEntity entity = new EmailFilterProposalEntity();
    entity.setUserId(userId);
    entity.setMatchId(matchId);
    entity.setFilterId(3L);
    entity.setToolName("create_task_in_project");
    entity.setToolTitle("Create task");
    entity.setToolDescription("Creates a task");
    entity.setArguments("{\"title\":\"Pay\"}");
    entity.setCallHash("hash-" + matchId);
    entity.setStatus("PROPOSED");
    entity.setCreatedDate(new Date(NOW));
    entity.setExpiresDate(new Date(NOW + 5_000));
    entity.setConversationId("run-1");
    return entity;
  }
}
