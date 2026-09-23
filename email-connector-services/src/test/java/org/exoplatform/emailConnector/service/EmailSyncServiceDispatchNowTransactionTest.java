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
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.emailConnector.dao.EmailSyncStateDAO;
import org.exoplatform.emailConnector.entity.EmailSyncStateEntity;
import org.exoplatform.emailConnector.storage.EmailSyncStateStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;

/**
 * The transaction boundary of {@link EmailSyncService#dispatchNow} (EXO-90573), through
 * the real Spring proxy, the real storage and DAO, and the real transaction manager over
 * the shipped changelog. A mock-based test cannot see it: what matters is when the
 * {@code REQUIRES_NEW} transaction commits relative to the moment the mailbox reaches the
 * executor.
 * <p>
 * The executor is a recording stub: its {@code execute} does not run the task (the run is
 * {@code @ContainerTransactional} and would boot a portal container), it looks, from
 * another thread and so another connection, at whether the claim is committed at that
 * instant. HSQLDB's default {@code LOCKS} transaction model blocks that read while the
 * claiming transaction holds the table lock, so a read that does not answer within the timeout counts as "not
 * committed", as does one that answers with the row unclaimed.
 * <p>
 * The test class runs outside any transaction, as the {@code AFTER_COMMIT} listener that
 * calls the method does.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import({ EmailSyncStateStorage.class, EmailSyncService.class })
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EmailSyncServiceDispatchNowTransactionTest {

  private static final String     USER = "dispatchNowUser";

  @Autowired
  private EmailSyncService        emailSyncService;

  @Autowired
  private EmailSyncStateDAO       emailSyncStateDAO;

  @Autowired
  private DataSource              dataSource;

  @MockitoSpyBean
  private EmailSyncStateStorage   emailSyncStateStorage;

  @MockitoBean
  private EmailBoxService         emailBoxService;

  @MockitoBean
  private EmailConnectorService   emailConnectorService;

  @MockitoBean
  private UserEmailSettingService userEmailSettingService;

  @MockitoBean
  private SettingService          settingService;

  private RecordingExecutor       executor;

  private ExecutorService         reader;

  /**
   * The minimal Spring slice: the add-on's entities and repositories, over Boot's
   * auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailSyncStateEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailSyncStateDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * One committed, unclaimed sync-state row, and the recording executor in place of the
   * service's pool.
   */
  @BeforeEach
  void anUnclaimedMailboxAndARecordingExecutor() {
    emailSyncStateDAO.deleteAll();
    Date past = new Date(System.currentTimeMillis() - 3_600_000L);
    emailSyncStateDAO.save(new EmailSyncStateEntity(USER, null, null, past, past, past, null, 0L));
    reader = Executors.newSingleThreadExecutor();
    executor = new RecordingExecutor(new JdbcTemplate(dataSource), reader);
    EmailSyncService target = AopTestUtils.getTargetObject(emailSyncService);
    ReflectionTestUtils.setField(target, "executor", executor);
  }

  /**
   * The reader thread is stopped, and committed rows are this class's to remove.
   */
  @AfterEach
  void cleanUp() {
    reader.shutdownNow();
    emailSyncStateDAO.deleteAll();
  }

  /**
   * The context under test is the proxied one: without it, this class would prove
   * nothing about transaction boundaries.
   */
  @Test
  void theServiceIsTheTransactionalProxy() {
    assertTrue(AopUtils.isAopProxy(emailSyncService));
  }

  /**
   * The mailbox reaches the executor once, and only after its claim is committed: another
   * connection already sees the row claimed by this node when {@code execute} is called.
   */
  @Test
  void theMailboxIsHandedOverOnlyOnceItsClaimIsCommitted() {
    assertTrue(emailSyncService.dispatchNow(USER));

    assertEquals(1, executor.claimSeenAtHandOver.size(), "the mailbox must be handed to the executor once");
    assertEquals(EmailConnectorUtils.getSyncNodeName(),
                 executor.claimSeenAtHandOver.get(0),
                 "the executor received the mailbox while its claim was not committed yet");
  }

  /**
   * A claim whose transaction fails to commit is never handed over: nothing runs on a
   * claim that was rolled back, and the row is left unclaimed for the next tick.
   */
  @Test
  void aClaimWhoseCommitFailsIsNeverHandedOver() {
    doAnswer(invocation -> {
      Object claimed = invocation.callRealMethod();
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        /** Makes the claiming transaction's commit fail. */
        @Override
        public void beforeCommit(boolean readOnly) {
          throw new IllegalStateException("the commit fails");
        }
      });
      return claimed;
    }).when(emailSyncStateStorage).claim(anyString(), any(Date.class), anyString(), any(Date.class));

    assertThrows(IllegalStateException.class, () -> emailSyncService.dispatchNow(USER));

    assertEquals(Collections.emptyList(), executor.claimSeenAtHandOver, "a rolled-back claim was handed to the executor");
    assertNull(emailSyncStateDAO.findById(USER).orElseThrow().getClaimedBy(), "the claim must have been rolled back");
  }

  /**
   * An executor that runs nothing: each {@code execute} records the claimant another
   * connection sees on the row at that instant, or null when the read is blocked by an
   * uncommitted write.
   */
  static final class RecordingExecutor extends ThreadPoolExecutor {

    private final JdbcTemplate    jdbcTemplate;

    private final ExecutorService reader;

    final List<String>         claimSeenAtHandOver = Collections.synchronizedList(new ArrayList<>());

    /**
     * Four idle threads' worth of free slots, never started.
     *
     * @param jdbcTemplate the template the committed state is read through
     * @param reader the thread, of this test's own, the read runs on
     */
    RecordingExecutor(JdbcTemplate jdbcTemplate, ExecutorService reader) {
      super(4, 4, 1, TimeUnit.MINUTES, new ArrayBlockingQueue<>(4));
      this.jdbcTemplate = jdbcTemplate;
      this.reader = reader;
    }

    /**
     * Records the claimant visible from another connection, without running the task.
     *
     * @param command the dispatched run, dropped
     */
    @Override
    public void execute(Runnable command) {
      CompletableFuture<String> read = CompletableFuture.supplyAsync(() -> jdbcTemplate.queryForObject(
                                                                                                        "SELECT CLAIMED_BY FROM EMAIL_SYNC_STATE WHERE USER_ID = ?",
                                                                                                        String.class,
                                                                                                        USER),
                                                                     reader);
      String claimant;
      try {
        claimant = read.get(3, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        claimant = null;
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      claimSeenAtHandOver.add(claimant);
    }
  }
}
