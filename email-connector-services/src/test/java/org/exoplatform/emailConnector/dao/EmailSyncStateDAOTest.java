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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import org.exoplatform.emailConnector.entity.EmailSyncStateEntity;

/**
 * Every statement of the sync-state table, executed by a real engine (in-memory
 * HSQLDB) through the real repository proxy.
 * <p>
 * The claim is the whole design: a conditional UPDATE whose row count decides who
 * synchronizes a mailbox. A mocked DAO answers whatever the test says; only the
 * engine can show that two claims on one row yield one and zero, that a stale claim
 * is taken over and a live one is not, that a release by the wrong node touches
 * nothing, and that the two-tier due-query picks the rows it should at each
 * boundary and orders the never-synchronized ones first. What this run cannot show
 * is that the LAST_SYNC_DATE index serves the ORDER BY on MySQL or PostgreSQL --
 * HSQLDB's planner is not theirs -- and it does not claim to.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop" })
public class EmailSyncStateDAOTest {

  private static final String NODE_A    = "node-a";

  private static final String NODE_B    = "node-b";

  private static final long   MINUTE    = 60_000L;

  // A fixed "now", so every threshold below is an exact offset rather than a race
  // against the clock.
  private static final Date   NOW       = new Date(1_800_000_000_000L);

  private static final Date   EPOCH     = new Date(0);

  @Autowired
  private TestEntityManager   entityManager;

  @Autowired
  private EmailSyncStateDAO   emailSyncStateDAO;

  /**
   * The minimal Spring slice: this add-on's entities and repositories, with Boot's
   * auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailSyncStateEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailSyncStateDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * Two callers claim one unclaimed mailbox: the first gets one row, the second
   * zero, and the row names the first.
   */
  @Test
  void theClaimIsWonOnceAndLostAfterwards() {
    persist("alice", null, null, minutesAgo(30), null);

    assertEquals(1, emailSyncStateDAO.claim("alice", NOW, NODE_A, staleBefore()));
    assertEquals(0, emailSyncStateDAO.claim("alice", NOW, NODE_B, staleBefore()));

    EmailSyncStateEntity row = reload("alice");
    assertEquals(NODE_A, row.getClaimedBy());
    assertEquals(NOW, row.getSyncStartedDate());
  }

  /**
   * A claim older than the stale timeout is a dead node's, and anyone may take it;
   * a claim younger than it is a live run and nobody may.
   */
  @Test
  void aStaleClaimIsReclaimableAndALiveOneIsNot() {
    persist("crashed", minutesAgo(61), NODE_A, minutesAgo(90), null);
    persist("running", minutesAgo(59), NODE_A, minutesAgo(90), null);

    assertEquals(1, emailSyncStateDAO.claim("crashed", NOW, NODE_B, staleBefore()));
    assertEquals(0, emailSyncStateDAO.claim("running", NOW, NODE_B, staleBefore()));

    assertEquals(NODE_B, reload("crashed").getClaimedBy());
    assertEquals(NODE_A, reload("running").getClaimedBy());
  }

  /**
   * The release clears the claim and stamps the run's start as the last sync --
   * but only for the node that holds it: a run that outlived the timeout and finds
   * its mailbox re-claimed by another node must leave that claim alone.
   */
  @Test
  void onlyTheClaimingNodeReleases() {
    Date startedAt = minutesAgo(3);
    persist("alice", startedAt, NODE_A, minutesAgo(30), null);

    assertEquals(0, emailSyncStateDAO.release("alice", NODE_B, startedAt), "another node's release touches nothing");
    EmailSyncStateEntity still = reload("alice");
    assertEquals(NODE_A, still.getClaimedBy());
    assertEquals(minutesAgo(30), still.getLastSyncDate());

    assertEquals(1, emailSyncStateDAO.release("alice", NODE_A, startedAt));
    EmailSyncStateEntity released = reload("alice");
    assertNull(released.getClaimedBy());
    assertNull(released.getSyncStartedDate());
    assertEquals(startedAt, released.getLastSyncDate(), "the cadence is the run's START");
  }

  /**
   * A restarted node releases every claim it held, and only those; the cadence is
   * left where it was so the mailboxes are due again at once.
   */
  @Test
  void aNodeReleasesItsOwnClaimsOnRecovery() {
    persist("mine-1", minutesAgo(5), NODE_A, minutesAgo(20), null);
    persist("mine-2", minutesAgo(7), NODE_A, null, null);
    persist("theirs", minutesAgo(5), NODE_B, minutesAgo(20), null);
    persist("free", null, null, minutesAgo(20), null);

    assertEquals(2, emailSyncStateDAO.releaseClaimsOf(NODE_A));

    assertNull(reload("mine-1").getClaimedBy());
    assertEquals(minutesAgo(20), reload("mine-1").getLastSyncDate());
    assertNull(reload("mine-2").getClaimedBy());
    assertEquals(NODE_B, reload("theirs").getClaimedBy());
  }

  /**
   * The two tiers at their boundaries, in one query: with a 10-minute active period
   * and a 60-minute inactive one, an active user is due at eleven minutes and not at
   * nine; an inactive user is due at sixty-one and not at fifty-nine (nor at eleven);
   * a user with no activity stamp is inactive; a row nobody ever synchronized is due
   * whatever else it says; and a row under a live claim is never due.
   */
  @Test
  void theDueSelectionHonoursBothTiers() {
    Date activeSince = minutesAgo(14 * 24 * 60);
    Date activeDueBefore = minutesAgo(10);
    Date inactiveDueBefore = minutesAgo(60);
    persist("active-due", null, null, minutesAgo(11), minutesAgo(60));
    persist("active-fresh", null, null, minutesAgo(9), minutesAgo(60));
    persist("inactive-due", null, null, minutesAgo(62), minutesAgo(20 * 24 * 60));
    persist("inactive-fresh", null, null, minutesAgo(59), minutesAgo(20 * 24 * 60));
    persist("inactive-past-active-period", null, null, minutesAgo(11), minutesAgo(20 * 24 * 60));
    persist("unknown-activity-due", null, null, minutesAgo(61), null);
    persist("unknown-activity-fresh", null, null, minutesAgo(11), null);
    persist("never-synced", null, null, null, null);
    persist("claimed", minutesAgo(2), NODE_A, minutesAgo(61), null);

    List<String> due = emailSyncStateDAO.findDue(activeSince, activeDueBefore, inactiveDueBefore, staleBefore(), PageRequest.of(0, 100));

    assertEquals(List.of("never-synced", "inactive-due", "unknown-activity-due", "active-due"), due);
    assertEquals(4, emailSyncStateDAO.countDue(activeSince, activeDueBefore, inactiveDueBefore, staleBefore()));
  }

  /**
   * Task 1's wiring of the query -- everyone active, both periods the same -- is a
   * single tier: due after the period, not before, never-synchronized first, and
   * the oldest last sync ahead of the newer.
   */
  @Test
  void withOneTierEveryoneIsDueAfterThePeriodOldestFirst() {
    Date dueBefore = minutesAgo(10);
    persist("newer", null, null, minutesAgo(12), null);
    persist("older", null, null, minutesAgo(45), null);
    persist("fresh", null, null, minutesAgo(5), null);
    persist("never", null, null, null, null);

    List<String> due = emailSyncStateDAO.findDue(EPOCH, dueBefore, dueBefore, staleBefore(), PageRequest.of(0, 100));

    assertEquals(List.of("never", "older", "newer"), due);
  }

  /**
   * The page bound is honoured, and it cuts from the back: the mailboxes waiting
   * longest are the ones a full tick still serves.
   */
  @Test
  void thePageBoundKeepsTheOldest() {
    Date dueBefore = minutesAgo(10);
    persist("waited-45", null, null, minutesAgo(45), null);
    persist("waited-12", null, null, minutesAgo(12), null);
    persist("waited-30", null, null, minutesAgo(30), null);

    List<String> due = emailSyncStateDAO.findDue(EPOCH, dueBefore, dueBefore, staleBefore(), PageRequest.of(0, 2));

    assertEquals(List.of("waited-45", "waited-30"), due);
  }

  /**
   * The oldest wait, for the status line: the oldest last sync among the due rows,
   * a never-synchronized row counting from its creation; null when nothing is due.
   */
  @Test
  void theOldestDueIsReadForTheStatusLine() {
    Date dueBefore = minutesAgo(10);
    assertNull(emailSyncStateDAO.findOldestDue(EPOCH, dueBefore, dueBefore, staleBefore()), "nothing is due on an empty table");

    persist("waited-30", null, null, minutesAgo(30), null);
    persist("fresh", null, null, minutesAgo(5), null);
    assertEquals(minutesAgo(30), emailSyncStateDAO.findOldestDue(EPOCH, dueBefore, dueBefore, staleBefore()));

    EmailSyncStateEntity never = persist("never", null, null, null, null);
    never.setCreatedDate(minutesAgo(90));
    entityManager.flush();
    entityManager.clear();
    assertEquals(minutesAgo(90), emailSyncStateDAO.findOldestDue(EPOCH, dueBefore, dueBefore, staleBefore()));
  }

  /**
   * The activity stamp is throttled in SQL: written when the row has none or an old
   * one, left alone when the row's is recent enough, and the second answer is a row
   * count of zero rather than a second write.
   */
  @Test
  void theActivityStampIsThrottledInTheStatement() {
    persist("alice", null, null, minutesAgo(30), null);
    persist("bob", null, null, minutesAgo(30), minutesAgo(3));
    persist("carol", null, null, minutesAgo(30), minutesAgo(15));
    Date throttleBefore = minutesAgo(10);

    assertEquals(1, emailSyncStateDAO.touchActivity("alice", NOW, throttleBefore), "no stamp yet: written");
    assertEquals(0, emailSyncStateDAO.touchActivity("bob", NOW, throttleBefore), "three minutes old: kept");
    assertEquals(1, emailSyncStateDAO.touchActivity("carol", NOW, throttleBefore), "fifteen minutes old: written");

    assertEquals(NOW, reload("alice").getLastActivityDate());
    assertEquals(minutesAgo(3), reload("bob").getLastActivityDate());
    assertEquals(NOW, reload("carol").getLastActivityDate());
  }

  /**
   * Resetting a row's schedule touches the two schedule columns and not the claim:
   * a mailbox being synchronized at that very moment keeps its claim.
   */
  @Test
  void resettingTheScheduleLeavesTheClaimAlone() {
    persist("alice", minutesAgo(2), NODE_A, minutesAgo(30), minutesAgo(30));

    assertEquals(1, emailSyncStateDAO.resetSchedule("alice", null, NOW));
    assertEquals(0, emailSyncStateDAO.resetSchedule("nobody", null, NOW));

    EmailSyncStateEntity row = reload("alice");
    assertNull(row.getLastSyncDate());
    assertEquals(NOW, row.getLastActivityDate());
    assertEquals(NODE_A, row.getClaimedBy());
    assertEquals(minutesAgo(2), row.getSyncStartedDate());
  }

  /**
   * The live-claim count for the status line: claims within the timeout count,
   * stale ones do not.
   */
  @Test
  void liveClaimsAreCountedAndStaleOnesAreNot() {
    persist("live", minutesAgo(5), NODE_A, null, null);
    persist("stale", minutesAgo(61), NODE_A, null, null);
    persist("free", null, null, null, null);

    assertEquals(1, emailSyncStateDAO.countClaimed(staleBefore()));
  }

  /**
   * Persists one row and flushes it, so every statement under test reads it from
   * the database rather than from the persistence context.
   *
   * @param userId the mailbox owner
   * @param syncStartedDate the claim, or null
   * @param claimedBy the claiming node, or null
   * @param lastSyncDate the cadence, or null for never synchronized
   * @param lastActivityDate the activity stamp, or null
   * @return the persisted row
   */
  private EmailSyncStateEntity persist(String userId, Date syncStartedDate, String claimedBy, Date lastSyncDate, Date lastActivityDate) {
    EmailSyncStateEntity row = new EmailSyncStateEntity(userId, syncStartedDate, claimedBy, lastSyncDate, lastActivityDate, NOW);
    entityManager.persist(row);
    entityManager.flush();
    return row;
  }

  /**
   * One row, read fresh from the database.
   *
   * @param userId the mailbox owner
   * @return the row
   */
  private EmailSyncStateEntity reload(String userId) {
    entityManager.clear();
    EmailSyncStateEntity row = emailSyncStateDAO.findById(userId).orElse(null);
    assertNotNull(row, userId + " has a row");
    return row;
  }

  /**
   * The instant {@code minutes} before the fixed now.
   *
   * @param minutes how many minutes back
   * @return the instant
   */
  private static Date minutesAgo(long minutes) {
    return new Date(NOW.getTime() - minutes * MINUTE);
  }

  /**
   * The sixty-minute stale threshold, from the fixed now.
   *
   * @return the instant before which a claim is stale
   */
  private static Date staleBefore() {
    return minutesAgo(60);
  }
}
