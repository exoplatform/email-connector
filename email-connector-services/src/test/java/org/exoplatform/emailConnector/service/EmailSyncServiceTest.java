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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.emailConnector.model.EmailSyncExecutorStatus;
import org.exoplatform.emailConnector.model.EmailSyncState;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailSyncStateStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;

/**
 * The dispatcher's one tick, against a mocked storage and an executor that runs
 * on the calling thread.
 * <p>
 * The executor is replaced on purpose. The real one runs each claimed mailbox on
 * a daemon thread, and {@code runClaimed} carries {@code @ContainerTransactional},
 * whose woven aspect reads the current container; on a pool thread of a unit test
 * that is the JVM-wide root one, and the aspect then calls
 * {@code PortalContainer.getInstance()}, which tries to build a real portal and dies
 * on the first add-on missing from this module's classpath. That is the annotation
 * working, not failing. So the container is <i>stated</i> here -- the
 * {@code mockStatic} discipline {@code CaldavSyncSweepJobTest} uses -- and since a
 * mocked static is scoped to the thread that mocked it, the tasks must run on this
 * thread: an executor whose {@code execute} runs the task inline does that, while
 * still being the {@link ThreadPoolExecutor} the service sizes and reads.
 */
@ExtendWith(MockitoExtension.class)
public class EmailSyncServiceTest {

  private static final String               ALICE = "alice";

  private static final String               BOB   = "bob";

  @Mock
  private EmailBoxService                   emailBoxService;

  @Mock
  private EmailSyncStateStorage             emailSyncStateStorage;

  @Mock
  private EmailConnectorService             emailConnectorService;

  @Mock
  private UserEmailSettingService           userEmailSettingService;

  @Mock
  private SettingService                    settingService;

  @Mock
  private ExoContainer                      container;

  @InjectMocks
  private EmailSyncService                  emailSyncService;

  private MockedStatic<ExoContainerContext> containerContext;

  private InlineExecutor                    executor;

  /**
   * States a container the woven aspect can work with, gives the service an
   * executor that runs on this thread, and defaults the administered bounds.
   */
  @BeforeEach
  void establishAContainerAndAnInlineExecutor() {
    containerContext = mockStatic(ExoContainerContext.class);
    containerContext.when(ExoContainerContext::getCurrentContainer).thenReturn(container);
    executor = new InlineExecutor(4);
    ReflectionTestUtils.setField(emailSyncService, "executor", executor);
    ReflectionTestUtils.setField(emailSyncService, "recovered", true);
    lenient().when(emailConnectorService.getEmailSyncThreads()).thenReturn(4);
    lenient().when(emailConnectorService.getEmailBoxSyncPeriod()).thenReturn(10);
  }

  /**
   * Takes the stated container away again: a static left mocked would be read by
   * whatever runs next in this fork.
   */
  @AfterEach
  void forgetTheContainer() {
    containerContext.close();
  }

  /**
   * One tick claims only what can run now: with two of four threads busy, the
   * due-query is bounded to two, and only the rows that answer are claimed and
   * submitted.
   */
  @Test
  void aTickClaimsOnlyTheFreeSlots() throws Exception {
    executor.active = 2;
    when(emailSyncStateStorage.findDue(any(Date.class), any(Date.class), any(Date.class), any(Date.class), eq(2))).thenReturn(List.of(ALICE, BOB));
    when(emailSyncStateStorage.claim(anyString(), any(Date.class), anyString(), any(Date.class))).thenReturn(true);

    assertEquals(2, emailSyncService.dispatchDueSyncs());

    verify(emailSyncStateStorage).findDue(any(Date.class), any(Date.class), any(Date.class), any(Date.class), eq(2));
    verify(emailBoxService).synchronizeClaimed(ALICE);
    verify(emailBoxService).synchronizeClaimed(BOB);
  }

  /**
   * A full executor claims nothing: a claim on a mailbox nothing can run now would
   * hold it for nobody.
   */
  @Test
  void aFullExecutorClaimsNothing() {
    executor.active = 4;

    assertEquals(0, emailSyncService.dispatchDueSyncs());

    verify(emailSyncStateStorage, never()).findDue(any(Date.class), any(Date.class), any(Date.class), any(Date.class), anyInt());
    verify(emailSyncStateStorage, never()).claim(anyString(), any(Date.class), anyString(), any(Date.class));
  }

  /**
   * The bounds are read at each tick: the period the due-query is computed from is
   * the administered one, and a change is seen at the very next tick.
   */
  @Test
  void thePeriodIsReadAtEachTick() {
    when(emailConnectorService.getEmailBoxSyncPeriod()).thenReturn(10, 30);
    Date beforeFirst = new Date();
    emailSyncService.dispatchDueSyncs();
    emailSyncService.dispatchDueSyncs();

    verify(emailSyncStateStorage, times(2)).findDue(any(Date.class), any(Date.class), any(Date.class), any(Date.class), eq(4));
    verify(emailSyncStateStorage).findDue(eq(new Date(0)),
                                          org.mockito.ArgumentMatchers.argThat(due -> withinMinutesBefore(due, beforeFirst, 10)),
                                          any(Date.class),
                                          any(Date.class),
                                          eq(4));
    verify(emailSyncStateStorage).findDue(eq(new Date(0)),
                                          org.mockito.ArgumentMatchers.argThat(due -> withinMinutesBefore(due, beforeFirst, 30)),
                                          any(Date.class),
                                          any(Date.class),
                                          eq(4));
  }

  /**
   * A lost claim -- another node won the row between the SELECT and the UPDATE -- is
   * the mechanism working: the mailbox is skipped without an error and without a
   * release, and the next mailbox of the same tick is still dispatched.
   */
  @Test
  void aLostClaimIsSkippedWithoutError() throws Exception {
    when(emailSyncStateStorage.findDue(any(Date.class), any(Date.class), any(Date.class), any(Date.class), anyInt())).thenReturn(List.of(ALICE, BOB));
    when(emailSyncStateStorage.claim(eq(ALICE), any(Date.class), anyString(), any(Date.class))).thenReturn(false);
    when(emailSyncStateStorage.claim(eq(BOB), any(Date.class), anyString(), any(Date.class))).thenReturn(true);

    assertEquals(1, emailSyncService.dispatchDueSyncs());

    verify(emailBoxService, never()).synchronizeClaimed(ALICE);
    verify(emailBoxService).synchronizeClaimed(BOB);
    verify(emailSyncStateStorage, never()).release(eq(ALICE), anyString(), any(Date.class));
  }

  /**
   * A throwing sync still releases its claim: a claim that outlives its run locks
   * the mailbox out of every node for the whole stale timeout. Mutation-verified:
   * with the release out of the {@code finally}, this fails.
   */
  @Test
  void aThrowingSyncReleasesTheClaim() throws Exception {
    Date startedAt = new Date();
    when(emailBoxService.synchronizeClaimed(ALICE)).thenThrow(new IllegalStateException("the machinery broke"));

    assertDoesNotThrow(() -> emailSyncService.runClaimed(ALICE, startedAt));

    verify(emailSyncStateStorage).release(ALICE, EmailConnectorUtils.getSyncNodeName(), startedAt);
  }

  /**
   * A {@code LinkageError} thrown by one mailbox's sync (a missing optional
   * dependency surfacing as a {@code NoClassDefFoundError}, the CalDAV sweep's
   * lesson) is an Error, not an exception: it still releases that claim and the next
   * mailbox of the same tick is still dispatched. Mutation-verified: with the guard
   * narrowed to {@code RuntimeException}, this fails.
   */
  @Test
  void aLinkageErrorReleasesTheClaimAndTheTickGoesOn() throws Exception {
    when(emailSyncStateStorage.findDue(any(Date.class), any(Date.class), any(Date.class), any(Date.class), anyInt())).thenReturn(List.of(ALICE, BOB));
    when(emailSyncStateStorage.claim(anyString(), any(Date.class), anyString(), any(Date.class))).thenReturn(true);
    when(emailBoxService.synchronizeClaimed(ALICE)).thenThrow(new NoClassDefFoundError("org/apache/commons/validator/routines/EmailValidator"));

    assertDoesNotThrow(() -> emailSyncService.dispatchDueSyncs());

    verify(emailSyncStateStorage).release(eq(ALICE), eq(EmailConnectorUtils.getSyncNodeName()), any(Date.class));
    verify(emailBoxService).synchronizeClaimed(BOB);
    verify(emailSyncStateStorage).release(eq(BOB), eq(EmailConnectorUtils.getSyncNodeName()), any(Date.class));
  }

  /**
   * A due row whose mailbox is bound to no connector any more is an orphan: the
   * row is dropped rather than claimed again every period forever.
   */
  @Test
  void anOrphanRowIsDeleted() throws Exception {
    when(emailBoxService.synchronizeClaimed(ALICE)).thenThrow(new IllegalAccessException("no connector"));

    emailSyncService.runClaimed(ALICE, new Date());

    verify(emailSyncStateStorage).delete(ALICE);
  }

  /**
   * Recovery runs once per boot, on the first tick: this node's leftover claims are
   * released, and the second tick does not do it again.
   */
  @Test
  void recoveryRunsOncePerBoot() {
    ReflectionTestUtils.setField(emailSyncService, "recovered", false);
    when(settingService.getContextsByTypeAndScopeAndSettingName(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of());

    emailSyncService.dispatchDueSyncs();
    emailSyncService.dispatchDueSyncs();

    verify(emailSyncStateStorage, times(1)).releaseClaimsOf(EmailConnectorUtils.getSyncNodeName());
  }

  /**
   * The reconciliation of the first tick, which is also the upgrade from the
   * per-user Quartz jobs: every connected user without a row gets one carrying the
   * cadence their settings remember and stamped active now; a user who already has
   * a row is only stamped; a settings entry bound to no connector gets no row.
   */
  @Test
  void reconciliationRegistersEveryConnectedMailbox() {
    ReflectionTestUtils.setField(emailSyncService, "recovered", false);
    when(settingService.getContextsByTypeAndScopeAndSettingName(Context.USER.getName(),
                                                                 Scope.APPLICATION.getName(),
                                                                 EmailConnectorService.EMAIL_CONNECTOR_SCOPE_ID,
                                                                 EmailConnectorService.USER_EMAIL_SETTING_KEY,
                                                                 0,
                                                                 Integer.MAX_VALUE)).thenReturn(List.of(Context.USER.id(ALICE),
                                                                                                        Context.USER.id(BOB),
                                                                                                        Context.USER.id("known"),
                                                                                                        Context.USER.id("disconnected")));
    long aliceLastSync = 1_700_000_000_000L;
    when(userEmailSettingService.getUserEmailSetting(ALICE)).thenReturn(new UserEmailSetting("1", "a@x", "p", null, null, 0, aliceLastSync, null, null, "c", true));
    when(userEmailSettingService.getUserEmailSetting(BOB)).thenReturn(new UserEmailSetting("1", "b@x", "p", null, null, 0, 0L, null, null, "c", true));
    when(userEmailSettingService.getUserEmailSetting("disconnected")).thenReturn(new UserEmailSetting());
    when(emailSyncStateStorage.get(anyString())).thenReturn(null);
    when(emailSyncStateStorage.get("known")).thenReturn(new EmailSyncState("known", null, null, new Date(), null, new Date()));

    emailSyncService.dispatchDueSyncs();

    verify(emailSyncStateStorage).upsert(eq(ALICE), eq(new Date(aliceLastSync)), any(Date.class));
    verify(emailSyncStateStorage).upsert(eq(BOB), isNull(), any(Date.class));
    verify(emailSyncStateStorage).touchActivity(eq("known"), any(Date.class), any(Date.class));
    verify(emailSyncStateStorage, never()).upsert(eq("known"), any(), any());
    verify(emailSyncStateStorage, never()).upsert(eq("disconnected"), any(), any());
  }

  /**
   * One user whose settings cannot be read must not stop the reconciliation of
   * everyone else.
   */
  @Test
  void reconciliationSkipsAFailingUserWithoutStoppingTheRest() {
    ReflectionTestUtils.setField(emailSyncService, "recovered", false);
    when(settingService.getContextsByTypeAndScopeAndSettingName(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                                                                                                                                            .thenReturn(List.of(Context.USER.id("broken"),
                                                                                                                                                                Context.USER.id(BOB)));
    when(userEmailSettingService.getUserEmailSetting("broken")).thenThrow(new RuntimeException("stored setting is unreadable"));
    when(userEmailSettingService.getUserEmailSetting(BOB)).thenReturn(new UserEmailSetting("1", "b@x", "p", null, null, 0, 0L, null, null, "c", true));

    assertDoesNotThrow(() -> emailSyncService.dispatchDueSyncs());

    verify(emailSyncStateStorage).upsert(eq(BOB), isNull(), any(Date.class));
  }

  /**
   * The pool follows the administered size at the next tick, up and down, without
   * a restart.
   */
  @Test
  void thePoolIsResizedWhenTheSettingChanges() {
    when(emailConnectorService.getEmailSyncThreads()).thenReturn(8, 2);

    emailSyncService.dispatchDueSyncs();
    assertEquals(8, executor.getCorePoolSize());
    assertEquals(8, executor.getMaximumPoolSize());

    emailSyncService.dispatchDueSyncs();
    assertEquals(2, executor.getCorePoolSize());
    assertEquals(2, executor.getMaximumPoolSize());
  }

  /**
   * The status line's numbers come from where they should: the executor for this
   * node, the table for the cluster and the backlog.
   */
  @Test
  void theStatusSnapshotReadsTheExecutorAndTheTable() {
    executor.active = 3;
    when(emailSyncStateStorage.countDue(any(Date.class), any(Date.class), any(Date.class), any(Date.class))).thenReturn(7L);
    when(emailSyncStateStorage.findOldestDue(any(Date.class), any(Date.class), any(Date.class), any(Date.class))).thenReturn(new Date(System.currentTimeMillis() - 25 * 60_000L));
    when(emailSyncStateStorage.countClaimed(any(Date.class))).thenReturn(5L);
    when(emailSyncStateStorage.count()).thenReturn(1040L);

    EmailSyncExecutorStatus status = emailSyncService.getStatus();

    assertEquals(EmailConnectorUtils.getSyncNodeName(), status.getNode());
    assertEquals(3, status.getRunning());
    assertEquals(0, status.getQueued());
    assertEquals(4, status.getThreads());
    assertEquals(5, status.getClaimed());
    assertEquals(7, status.getDueBacklog());
    assertEquals(25, status.getOldestDueMinutes());
    assertEquals(1040, status.getConnectedMailboxes());
  }

  /**
   * Whether {@code due} is {@code minutes} before an instant taken just before the
   * call, give or take the seconds the test itself took.
   *
   * @param due the threshold the service computed
   * @param reference an instant taken just before the call
   * @param minutes the expected offset
   * @return true when the offset matches to the second
   */
  private static boolean withinMinutesBefore(Date due, Date reference, int minutes) {
    long expected = reference.getTime() - minutes * 60_000L;
    return Math.abs(due.getTime() - expected) < 5_000L;
  }

  /**
   * A real {@link ThreadPoolExecutor} whose tasks run on the submitting thread, and
   * whose busy-thread count is whatever the test says: the service sizes it, reads
   * it and submits to it exactly as it does the production one, while every task
   * runs where the stated container is visible.
   */
  private static class InlineExecutor extends ThreadPoolExecutor {

    private int active;

    /**
     * A pool of the given size that never starts a thread.
     *
     * @param threads the core and maximum size
     */
    InlineExecutor(int threads) {
      super(threads, threads, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(threads));
    }

    /**
     * Runs the task here and now.
     *
     * @param command the task
     */
    @Override
    public void execute(Runnable command) {
      command.run();
    }

    /**
     * The busy-thread count the test set.
     *
     * @return the stated number of running tasks
     */
    @Override
    public int getActiveCount() {
      return active;
    }
  }
}
