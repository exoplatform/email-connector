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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
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
import org.exoplatform.emailConnector.dao.EmailFilterMatchDAO;
import org.exoplatform.emailConnector.entity.EmailFilterEntity;
import org.exoplatform.emailConnector.entity.EmailFilterMatchEntity;
import org.exoplatform.emailConnector.model.AppliedAction;
import org.exoplatform.emailConnector.model.EmailFilter;
import org.exoplatform.emailConnector.model.EmailFilterMatch;
import org.exoplatform.emailConnector.model.FilterAction;
import org.exoplatform.emailConnector.model.ServerRule;

import io.meeds.social.util.JsonUtils;

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
  private EmailFilterStorage  emailFilterStorage;

  @Autowired
  private EmailFilterMatchDAO emailFilterMatchDAO;

  @Autowired
  private EmailFilterDAO      emailFilterDAO;

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
   * The batch read by mails, outside any transaction as the auto-categoriser calls it:
   * each match with what it did, only for the Message-IDs asked, a row whose hash
   * collides with one of them but whose Message-ID differs left out.
   */
  @Test
  void theMatchesOfABatchOfMailsComeWithWhatTheyDid() {
    EmailFilterMatch categorised = emailFilterStorage.createMatch(match(7L, "<a@acme.com>"), "bob").orElseThrow();
    categorised.setActions(List.of(new AppliedAction("ADD_CATEGORY", true, null, null, null, 12L, null, null, null, false)));
    emailFilterStorage.updateMatch(categorised, "bob");
    emailFilterStorage.createMatch(match(7L, "<b@acme.com>"), "bob").orElseThrow();
    emailFilterStorage.createMatch(match(7L, "<c@acme.com>"), "bob").orElseThrow();
    EmailFilterMatchEntity collision = new EmailFilterMatchEntity();
    collision.setUserId("bob");
    collision.setFilterId(9L);
    collision.setMailHeaderId("<collision@acme.com>");
    collision.setMailHeaderHash(EmailFilterStorage.hash("<a@acme.com>"));
    collision.setMatchedDate(new Date());
    collision.setCreatedDate(new Date());
    collision.setAgentStatus(EmailFilterMatch.AGENT_NONE);
    emailFilterMatchDAO.saveAndFlush(collision);

    List<EmailFilterMatch> found = emailFilterStorage.getMatchesOfMails("bob", List.of("<a@acme.com>", "<b@acme.com>", "<z@acme.com>"));

    assertEquals(List.of("<a@acme.com>", "<b@acme.com>"), found.stream().map(EmailFilterMatch::getMailHeaderId).sorted().toList());
    AppliedAction action = found.stream()
                                .filter(match -> match.getMailHeaderId().equals("<a@acme.com>"))
                                .findFirst()
                                .orElseThrow()
                                .getActions()
                                .get(0);
    assertEquals("ADD_CATEGORY", action.type());
    assertEquals(12L, action.categoryId());
    assertTrue(action.ok());
    assertTrue(emailFilterStorage.getMatchesOfMails("bob", List.of()).isEmpty());

    List<String> many = new ArrayList<>(IntStream.range(0, 600).mapToObj(i -> "<none-" + i + "@acme.com>").toList());
    many.add("<c@acme.com>");
    assertEquals(List.of("<c@acme.com>"),
                 emailFilterStorage.getMatchesOfMails("bob", many).stream().map(EmailFilterMatch::getMailHeaderId).toList(),
                 "read past the first chunk");
  }

  /**
   * A new rule is assigned an id and every field it was given; an existing one is
   * updated in place, and an id naming nobody's rule is refused.
   */
  @Test
  void savingARuleCreatesUpdatesOrRefusesAnUnknownId() {
    EmailFilter created = emailFilterStorage.save("carl", filter(null, "Acme", 0), new Date());
    assertTrue(created.getId() > 0, "a create is assigned an id");
    assertEquals("Acme", emailFilterStorage.getFilter(created.getId(), "carl").orElseThrow().getName());

    created.setName("Acme renamed");
    EmailFilter updated = emailFilterStorage.save("carl", created, new Date());
    assertEquals(created.getId(), updated.getId(), "the same row, not a new one");
    assertEquals("Acme renamed", emailFilterStorage.getFilter(created.getId(), "carl").orElseThrow().getName());

    EmailFilter foreignId = filter(999L, "Nope", 0);
    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                     () -> emailFilterStorage.save("carl", foreignId, new Date()));
    assertEquals("emailConnector.filters.notFound", refusal.getMessage());
  }

  /**
   * A rule's own list, in position order; its keyword finds it back; only the enabled
   * ones are counted; the next position is one past the highest, or zero for a first
   * rule.
   */
  @Test
  void theOwnersRulesComeBackInOrderAndAreCounted() {
    assertEquals(0, emailFilterStorage.nextPosition("dana"), "no rule yet");

    EmailFilter second = filter(null, "Second", 1);
    second.setTagKeyword("kw-second");
    emailFilterStorage.save("dana", second, new Date());
    EmailFilter first = filter(null, "First", 0);
    first.setEnabled(true);
    emailFilterStorage.save("dana", first, new Date());

    assertEquals(List.of("First", "Second"), emailFilterStorage.getFilters("dana").stream().map(EmailFilter::getName).toList());
    assertEquals("Second", emailFilterStorage.getFilterByTag("dana", "kw-second").orElseThrow().getName());
    assertTrue(emailFilterStorage.getFilterByTag("dana", "no-such-keyword").isEmpty());
    assertEquals(1, emailFilterStorage.countEnabled("dana"), "only First is enabled");
    assertEquals(2, emailFilterStorage.nextPosition("dana"), "one past the highest");
  }

  /**
   * Deleting a rule removes only that owner's row; another owner's id, or one already
   * gone, is a no-op that says so.
   */
  @Test
  void deleteRemovesOnlyTheOwnersRow() {
    EmailFilter saved = emailFilterStorage.save("eve", filter(null, "Mine", 0), new Date());

    assertFalse(emailFilterStorage.delete(saved.getId(), "somebody-else"), "not this owner's rule");
    assertTrue(emailFilterStorage.delete(saved.getId(), "eve"));
    assertFalse(emailFilterStorage.delete(saved.getId(), "eve"), "already gone");
  }

  /**
   * Reordering only touches the rows whose computed position actually changed: one not
   * named in the new order keeps its position, and one already at its new slot is left
   * alone.
   */
  @Test
  void reorderOnlyTouchesRulesWhosePositionMoved() {
    EmailFilter a = emailFilterStorage.save("frank", filter(null, "A", 0), new Date());
    EmailFilter b = emailFilterStorage.save("frank", filter(null, "B", 1), new Date());
    EmailFilter c = emailFilterStorage.save("frank", filter(null, "C", 2), new Date());
    EmailFilter untouched = emailFilterStorage.save("george", filter(null, "Other owner", 0), new Date());

    // C is not named in the new order: indexOf answers -1, left at its position.
    emailFilterStorage.reorder("frank", List.of(b.getId(), a.getId()), new Date());

    List<EmailFilter> reordered = emailFilterStorage.getFilters("frank");
    assertEquals(List.of("B", "A", "C"), reordered.stream().map(EmailFilter::getName).toList());
    assertEquals(untouched.getUpdatedDate(),
                emailFilterStorage.getFilter(untouched.getId(), "george").orElseThrow().getUpdatedDate(),
                "another owner's rule is never touched");
    Long cUpdatedDate = emailFilterStorage.getFilter(c.getId(), "frank").orElseThrow().getUpdatedDate();
    Long bUpdatedDateAfterFirstMove = emailFilterStorage.getFilter(b.getId(), "frank").orElseThrow().getUpdatedDate();

    // Reordering again with the rows already at their computed slot touches nothing:
    // without the guard, B's row would be written again -- with a new updated date --
    // for a position it already has.
    emailFilterStorage.reorder("frank", List.of(b.getId(), a.getId()), new Date());

    assertEquals(cUpdatedDate, emailFilterStorage.getFilter(c.getId(), "frank").orElseThrow().getUpdatedDate(), "still untouched");
    assertEquals(bUpdatedDateAfterFirstMove,
                emailFilterStorage.getFilter(b.getId(), "frank").orElseThrow().getUpdatedDate(),
                "already at its computed slot, left alone");
    assertEquals(List.of("B", "A", "C"), emailFilterStorage.getFilters("frank").stream().map(EmailFilter::getName).toList());
  }

  /**
   * The sync's own writes: counting a match bumps the counter and the last-match date in
   * SQL, and switching a rule off truncates the reason to the column's width.
   */
  @Test
  void addMatchesAndDisableWithErrorWriteInSql() {
    EmailFilter saved = emailFilterStorage.save("henry", filter(null, "Counted", 0), new Date());
    Date matchDate = new Date();

    emailFilterStorage.addMatches(saved.getId(), "henry", 3L, matchDate);
    EmailFilter afterMatch = emailFilterStorage.getFilter(saved.getId(), "henry").orElseThrow();
    assertEquals(3L, afterMatch.getMatchCount());
    assertEquals(matchDate.getTime(), afterMatch.getLastMatchDate());

    emailFilterStorage.disableWithError(saved.getId(), "henry", "emailConnector.filters.unreadable", new Date());
    EmailFilter afterDisable = emailFilterStorage.getFilter(saved.getId(), "henry").orElseThrow();
    assertFalse(afterDisable.isEnabled());
    assertEquals("emailConnector.filters.unreadable", afterDisable.getLastError());
  }

  /**
   * A match's lifecycle in the assistant's queue: it moves from PENDING to RUNNING only
   * while it is still PENDING, is counted while it is in a status, and is counted as
   * queued since a date whatever the queue's outcome.
   */
  @Test
  void aMatchMovesBetweenAgentStatusesOnlyWhileInTheExpectedOne() {
    EmailFilterMatch created = emailFilterStorage.createMatch(match(1L, "<queued@acme.com>"), "iris").orElseThrow();
    created.setAgentStatus(EmailFilterMatch.AGENT_PENDING);
    emailFilterStorage.updateMatch(created, "iris");

    assertTrue(emailFilterStorage.updateAgentStatusIf(created.getId(), EmailFilterMatch.AGENT_PENDING, EmailFilterMatch.AGENT_RUNNING),
              "still pending, moved");
    assertFalse(emailFilterStorage.updateAgentStatusIf(created.getId(), EmailFilterMatch.AGENT_PENDING, EmailFilterMatch.AGENT_RUNNING),
               "no longer pending, refused");
    assertEquals(1, emailFilterStorage.countByAgentStatus("iris", EmailFilterMatch.AGENT_RUNNING));
    assertEquals(1,
                emailFilterStorage.countQueuedSince("iris", List.of(EmailFilterMatch.AGENT_RUNNING), new Date(created.getMatchedDate() - 1)));
  }

  /**
   * The queue handler's own read, and the startup sweep's cross-user read with each
   * match's owner attached.
   */
  @Test
  void theQueueIsReadByOwnerAndAcrossOwnersWithTheirOwner() {
    EmailFilterMatch mine = emailFilterStorage.createMatch(match(1L, "<mine@acme.com>"), "jill").orElseThrow();
    mine.setAgentStatus(EmailFilterMatch.AGENT_PENDING);
    emailFilterStorage.updateMatch(mine, "jill");
    EmailFilterMatch theirs = emailFilterStorage.createMatch(match(1L, "<theirs@acme.com>"), "kate").orElseThrow();
    theirs.setAgentStatus(EmailFilterMatch.AGENT_PENDING);
    emailFilterStorage.updateMatch(theirs, "kate");

    assertEquals(1, emailFilterStorage.getMatchesByAgentStatus("jill", EmailFilterMatch.AGENT_PENDING, 10).size());

    List<EmailFilterStorage.OwnedMatch> owned =
                                               emailFilterStorage.getMatchesByAgentStatuses(List.of(EmailFilterMatch.AGENT_PENDING), 0, 10);
    assertEquals(List.of("jill", "kate"), owned.stream().map(EmailFilterStorage.OwnedMatch::userId).sorted().toList());
  }

  /**
   * The log of one rule, newest first, and the plain lookup of one rule's matches on a
   * named set of mails -- empty for no mails asked.
   */
  @Test
  void theLogAndTheRulesOwnMatchesComeNewestFirst() {
    EmailFilterMatch older = emailFilterStorage.createMatch(match(5L, "<older@acme.com>"), "liam").orElseThrow();
    emailFilterStorage.createMatch(match(5L, "<newer@acme.com>"), "liam");

    List<EmailFilterMatch> log = emailFilterStorage.getLog("liam", 5L, 10);
    assertEquals("<newer@acme.com>", log.get(0).getMailHeaderId(), "newest first");

    assertTrue(emailFilterStorage.getMatches("liam", 5L, List.of()).isEmpty());
    assertTrue(emailFilterStorage.getMatches("liam", 5L, null).isEmpty(), "no mails asked, none named");
    assertEquals(2, emailFilterStorage.getMatches("liam", 5L, List.of("<older@acme.com>", "<newer@acme.com>")).size());

    assertEquals("<older@acme.com>", emailFilterStorage.getMatch(older.getId(), "liam").orElseThrow().getMailHeaderId());
    assertTrue(emailFilterStorage.getMatch(older.getId(), "somebody-else").isEmpty(), "not this owner's match");
  }

  /**
   * The log's retention: only matches older than the cut-off, and only the owner's, are
   * pruned.
   */
  @Test
  void pruneMatchesDeletesOnlyTheOwnersOldRows() {
    Date past = new Date(System.currentTimeMillis() - 100_000);
    EmailFilterMatchEntity old = new EmailFilterMatchEntity();
    old.setUserId("mona");
    old.setFilterId(1L);
    old.setMailHeaderId("<old@acme.com>");
    old.setMailHeaderHash(EmailFilterStorage.hash("<old@acme.com>"));
    old.setMatchedDate(past);
    old.setCreatedDate(past);
    old.setAgentStatus(EmailFilterMatch.AGENT_NONE);
    emailFilterMatchDAO.saveAndFlush(old);
    emailFilterStorage.createMatch(match(1L, "<recent@acme.com>"), "mona");

    int pruned = emailFilterStorage.pruneMatches("mona", new Date(System.currentTimeMillis() - 50_000));

    assertEquals(1, pruned);
    assertTrue(emailFilterStorage.getMatchesOfMail("mona", "<old@acme.com>").isEmpty(), "the old row is gone");
    assertFalse(emailFilterStorage.getMatchesOfMail("mona", "<recent@acme.com>").isEmpty(), "the recent row stays");
  }

  /**
   * A rule or a match whose stored JSON cannot be read back answers null conditions,
   * null actions or an empty applied-actions record -- never an exception, and never an
   * empty list mistaken for "no condition". {@code JsonUtils} is stubbed, real methods
   * kept for everything else, because Jackson's own parse failure on genuinely malformed
   * text is a checked exception that this layer's {@code catch (RuntimeException e)}
   * does not see -- the case worth pinning is the one the code actually catches.
   */
  @Test
  void unreadableStoredJsonSurfacesAsNullNeverAsAnException() {
    try (MockedStatic<JsonUtils> jsonUtils = mockStatic(JsonUtils.class, CALLS_REAL_METHODS)) {
      jsonUtils.when(() -> JsonUtils.fromJsonString(eq("BROKEN_CONDITIONS"), eq(ServerRule.Condition[].class)))
               .thenThrow(new IllegalStateException("stubbed unreadable conditions"));
      jsonUtils.when(() -> JsonUtils.fromJsonString(eq("BROKEN_ACTIONS"), eq(FilterAction[].class)))
               .thenThrow(new IllegalStateException("stubbed unreadable actions"));
      jsonUtils.when(() -> JsonUtils.fromJsonString(eq("BROKEN_APPLIED"), eq(EmailFilterStorage.AppliedActions.class)))
               .thenThrow(new IllegalStateException("stubbed unreadable applied actions"));

      EmailFilterEntity badFilter = new EmailFilterEntity();
      badFilter.setUserId("noah");
      badFilter.setMailboxScope(EmailFilter.SCOPE_OWN);
      badFilter.setName("Broken");
      badFilter.setEnabled(true);
      badFilter.setKind(EmailFilter.KIND_EXO);
      badFilter.setMatchMode("ALL");
      badFilter.setConditions("BROKEN_CONDITIONS");
      badFilter.setActions("BROKEN_ACTIONS");
      badFilter.setCreatedDate(new Date());
      badFilter.setUpdatedDate(new Date());
      EmailFilterEntity savedBadFilter = emailFilterDAO.saveAndFlush(badFilter);

      EmailFilter read = emailFilterStorage.getFilter(savedBadFilter.getId(), "noah").orElseThrow();
      assertNull(read.getConditions());
      assertNull(read.getActions());

      EmailFilterMatchEntity badMatch = new EmailFilterMatchEntity();
      badMatch.setUserId("noah");
      badMatch.setFilterId(1L);
      badMatch.setMailHeaderId("<broken@acme.com>");
      badMatch.setMailHeaderHash(EmailFilterStorage.hash("<broken@acme.com>"));
      badMatch.setMatchedDate(new Date());
      badMatch.setCreatedDate(new Date());
      badMatch.setAgentStatus(EmailFilterMatch.AGENT_NONE);
      badMatch.setActionsApplied("BROKEN_APPLIED");
      emailFilterMatchDAO.saveAndFlush(badMatch);

      EmailFilterMatch readMatch = emailFilterStorage.getMatchesOfMail("noah", "<broken@acme.com>").get(0);
      assertNull(readMatch.getPostActionsState());
      assertTrue(readMatch.getActions().isEmpty());
    }
  }

  /**
   * A rule of its owner, as the settings drawer would submit it.
   *
   * @param id the rule's id, null to create it
   * @param name the name
   * @param position its place among the owner's rules
   * @return the rule
   */
  private static EmailFilter filter(Long id, String name, int position) {
    EmailFilter filter = new EmailFilter();
    filter.setId(id);
    filter.setName(name);
    filter.setEnabled(false);
    filter.setPosition(position);
    filter.setKind(EmailFilter.KIND_EXO);
    filter.setMailboxScope(EmailFilter.SCOPE_OWN);
    filter.setMatchAll(true);
    filter.setConditions(List.of());
    filter.setActions(List.of());
    return filter;
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
