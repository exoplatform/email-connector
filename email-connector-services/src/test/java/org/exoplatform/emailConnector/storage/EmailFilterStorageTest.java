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

import org.exoplatform.emailConnector.dao.EmailFilterDAO;
import org.exoplatform.emailConnector.entity.EmailFilterEntity;
import org.exoplatform.emailConnector.model.AppliedAction;
import org.exoplatform.emailConnector.model.EmailFilterMatch;

/**
 * The at-most-once decision on a real engine: the storage records a match, answers a
 * second one of the same rule on the same mail as "already handled", and the pass goes
 * on writing -- the refused insert does not poison what follows. Run outside a test
 * transaction, as the sync runs it: each repository call commits on its own.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = { "spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop" })
public class EmailFilterStorageTest {

  @Autowired
  private EmailFilterStorage emailFilterStorage;

  /**
   * The minimal Spring slice: the entities, their repositories and the storage.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailFilterEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailFilterDAO.class)
  @Import(EmailFilterStorage.class)
  static class JpaSliceConfiguration {
  }

  /**
   * A second match of one rule on one mail is refused, and the pass keeps writing.
   */
  @Test
  void aSecondMatchIsAlreadyHandledAndThePassGoesOn() {
    EmailFilterMatch first = emailFilterStorage.createMatch(match(7L, "<one@acme.com>"), "alice").orElseThrow();

    assertTrue(emailFilterStorage.createMatch(match(7L, "<one@acme.com>"), "alice").isEmpty(), "already handled");

    first.setActions(List.of(AppliedAction.applied("STAR")));
    EmailFilterMatch updated = emailFilterStorage.updateMatch(first, "alice");
    assertEquals("STAR", updated.getActions().get(0).type(), "the pass writes on after the refusal");
    assertTrue(emailFilterStorage.createMatch(match(8L, "<one@acme.com>"), "alice").isPresent(), "another rule on that mail");
    assertEquals(2, emailFilterStorage.getMatchesOfMail("alice", "<one@acme.com>").size());
  }

  /**
   * A match, as the service builds it.
   *
   * @param filterId the rule
   * @param headerId the mail's Message-ID
   * @return the match
   */
  private static EmailFilterMatch match(long filterId, String headerId) {
    EmailFilterMatch match = new EmailFilterMatch();
    match.setFilterId(filterId);
    match.setMailHeaderId(headerId);
    match.setMailRemoteId(1L);
    match.setMatchedDate(new Date().getTime());
    match.setActions(List.of());
    match.setAgentStatus(EmailFilterMatch.AGENT_NONE);
    match.setPostActionsState(EmailFilterMatch.POST_DONE);
    return match;
  }
}
