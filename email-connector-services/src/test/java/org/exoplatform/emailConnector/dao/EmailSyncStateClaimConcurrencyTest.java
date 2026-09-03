/*
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2020 - 2026 Meeds Association contact@meeds.io
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.emailConnector.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.entity.EmailSyncStateEntity;

/**
 * The claim under real contention.
 *
 * <p>
 * {@link EmailSyncStateDAOTest#theClaimIsWonOnceAndLostAfterwards} calls the claim
 * twice in a row, which proves the SQL predicate: once a row carries a fresh
 * {@code SYNC_STARTED_DATE}, a second claim no longer matches it. That is most of the
 * guarantee but not all of it, because the executor never claims sequentially -- every
 * node's tick fires at the same second, on its own thread, against one database.
 *
 * <p>
 * What is left to prove is the part the database owns rather than the predicate: that
 * two transactions issuing the same conditional UPDATE at the same instant cannot both
 * be told they affected a row. It rests on the write lock {@code UPDATE ... WHERE}
 * takes -- the second statement blocks, and when it proceeds it re-evaluates its
 * predicate against the committed value, matching nothing. Sequential calls never
 * exercise that, and a mock cannot: it is behaviour of the engine, so it needs the
 * engine.
 *
 * <p>
 * This lives in its own class because {@code @DataJpaTest} wraps each test in a
 * transaction it rolls back, and work that is never committed is invisible to another
 * thread -- the contention would be staged rather than real. The test method opts out
 * with {@link Propagation#NOT_SUPPORTED} so every write here is committed, and cleans
 * up after itself for the same reason.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop" })
public class EmailSyncStateClaimConcurrencyTest {

  private static final String USER      = "contended";

  private static final int    CLAIMANTS = 8;

  private static final Date   NOW       = new Date(1_800_000_000_000L);

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
   * Eight threads claim one unclaimed mailbox at the same instant: exactly one is told
   * it affected a row, and the row names that one.
   *
   * <p>
   * A latch releases them together so they contend rather than queue. Were the claim
   * not atomic -- a SELECT then an UPDATE, or an isolation level that let two writers
   * both see the row unclaimed -- more than one would be told it won, and the mailbox
   * would be synchronised several times over: the duplicate IMAP logins and duplicate
   * cached rows this design exists to prevent once every node runs its own tick.
   *
   * @throws Exception if a claimant thread does
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void exactlyOneOfManySimultaneousClaimantsWins() throws Exception {
    emailSyncStateDAO.save(new EmailSyncStateEntity(USER, null, null, new Date(NOW.getTime() - 1_800_000L), null, NOW));

    CountDownLatch startLine = new CountDownLatch(1);
    AtomicInteger winners = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(CLAIMANTS);
    try {
      List<Future<Integer>> races = new java.util.ArrayList<>();
      for (int i = 0; i < CLAIMANTS; i++) {
        String node = "node-" + i;
        Callable<Integer> claimant = () -> {
          startLine.await();
          int affected = emailSyncStateDAO.claim(USER, NOW, node, new Date(NOW.getTime() - 3_600_000L));
          if (affected > 0) {
            winners.incrementAndGet();
          }
          return affected;
        };
        races.add(pool.submit(claimant));
      }
      // Everyone is parked on the latch: releasing it puts the eight UPDATEs in flight
      // together, which is the only arrangement that tests the lock rather than the
      // order they happened to start in.
      startLine.countDown();
      int total = 0;
      for (Future<Integer> race : races) {
        total += race.get(30, TimeUnit.SECONDS);
      }
      assertEquals(1, winners.get(), "exactly one claimant is told it took the mailbox");
      assertEquals(1, total, "the claim affects exactly one row across every claimant");

      EmailSyncStateEntity row = emailSyncStateDAO.findById(USER).orElseThrow();
      assertEquals(NOW, row.getSyncStartedDate(), "the winner's claim is the one recorded");
      assertTrue(row.getClaimedBy() != null && row.getClaimedBy().startsWith("node-"), "the row names a claimant");
    } finally {
      pool.shutdownNow();
      emailSyncStateDAO.deleteById(USER);
    }
  }
}
