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

import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.emailConnector.model.EmailSyncExecutorStatus;
import org.exoplatform.emailConnector.model.EmailSyncState;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailSyncStateStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.common.ContainerTransactional;
import jakarta.annotation.PreDestroy;

/**
 * The mailbox synchronization dispatcher: the one place that decides which
 * mailboxes are synchronized when, in place of the per-user Quartz jobs the
 * add-on used to register.
 * <p>
 * The same shape as caldav-integration's sweep ({@code CaldavSyncService.sweepDueAccounts}),
 * plus the claim it lacks. That sweep selects a page of the accounts waiting
 * longest with a plain SELECT, and every guard it has -- last sync, "already
 * syncing", its executor -- lives in the process; under the cluster profile every
 * node therefore sweeps the same page. The tick that drives this service is a
 * Spring {@code @Scheduled} method too, node-local and firing on every node with
 * no coordination of its own, so the correctness across nodes is written here: a
 * mailbox is synchronized only by the caller whose conditional UPDATE on its
 * state row returned one ({@link EmailSyncStateStorage#claim}), the claim is
 * released whatever the run did, and a claim whose node died goes stale and is
 * taken over. Everything a Quartz job store gave for free (one runner per job,
 * recovery after a crash) is a column and a statement.
 * <p>
 * Bounded on purpose, in three ways. One tick claims at most the free slots of a
 * pool of {@code emailSyncThreads} daemon threads and stops: a claim on a mailbox
 * nothing can run now would only hold it for nobody. The due set is ONE query over
 * the state table, ordered oldest-first, never a walk of every user's settings.
 * And every bound -- the pool size, the period, the stale timeout -- is read at
 * each tick, never captured, so an administrator changing a value in the drawer
 * sees the next tick behave differently, not the next restart.
 * <p>
 * Task 1 of the sync-scheduling work wires the two-tier due-query with one tier:
 * everyone is active (an activity threshold at the epoch) and both periods are the
 * administration-wide one. The tiering task only changes those three parameters.
 */
@Service
public class EmailSyncService {

  private static final Log        LOG            = ExoLogger.getLogger(EmailSyncService.class);

  private static final String     THREAD_PREFIX  = "email-sync-";

  @Autowired
  private EmailBoxService         emailBoxService;

  @Autowired
  private EmailSyncStateStorage   emailSyncStateStorage;

  @Autowired
  private EmailConnectorService   emailConnectorService;

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  @Autowired
  private SettingService          settingService;

  private final AtomicInteger     threadNumber   = new AtomicInteger();

  // Built at the property default rather than the stored setting, because the
  // setting service is not a thing to read while the bean is being built; the first
  // tick sizes it to the administered value. The queue's capacity is fixed at that
  // boot size and only matters when the pool is later raised above it, which is why
  // the free-slot count below reads both the threads and the queue.
  private ThreadPoolExecutor      executor       = newExecutor(defaultThreads());

  // Recovery runs on the first tick, not in a @PostConstruct: a constructor-time or
  // startup thread has no portal container to write through (a write there fails,
  // and fails quietly), while the tick runs @ContainerTransactional on a job thread.
  private volatile boolean        recovered;

  /**
   * One tick of the dispatcher: recover once after boot, size the pool to the
   * administered value, then claim and submit as many due mailboxes as there are
   * free slots.
   * <p>
   * A lost claim (another node won the row between the SELECT and the UPDATE) is
   * not an error and not logged above DEBUG: it is the mechanism working. Each
   * mailbox is guarded on its own, {@code RuntimeException} and
   * {@code LinkageError} both, for the reason the CalDAV sweep learnt: a
   * {@code NoClassDefFoundError} from an optional dependency is an Error, walks
   * straight past a {@code RuntimeException} guard, and silently ended every one of
   * its runs mid-pass. A pass that dispatches mailboxes on everyone's behalf cannot
   * let one mailbox end the pass.
   *
   * @return how many mailboxes were claimed and handed to the executor
   */
  public int dispatchDueSyncs() {
    Date now = new Date();
    String node = EmailConnectorUtils.getSyncNodeName();
    if (!recovered) {
      recover(node, now);
      recovered = true;
    }
    resizePoolIfChanged();
    int free = freeSlots();
    if (free <= 0) {
      LOG.info("Mailbox sync executor is full on node {} ({} running, {} queued); nothing dispatched this tick",
               node,
               executor.getActiveCount(),
               executor.getQueue().size());
      return 0;
    }
    Date staleBefore = EmailConnectorUtils.getSyncClaimStaleBefore(now);
    Date activeDueBefore = activeDueBefore(now);
    List<String> due = emailSyncStateStorage.findDue(activeSince(), activeDueBefore, activeDueBefore, staleBefore, free);
    int dispatched = 0;
    for (String userId : due) {
      try {
        if (!emailSyncStateStorage.claim(userId, now, node, staleBefore)) {
          LOG.debug("The mailbox of user {} was claimed by another node first", userId);
          continue;
        }
        executor.execute(() -> runClaimed(userId, now));
        dispatched++;
      } catch (RuntimeException | LinkageError e) {
        LOG.warn("The mailbox of user {} could not be dispatched; it stays due for the next tick", userId, e);
      }
    }
    if (due.size() >= free) {
      LOG.info("Mailbox sync backlog on node {}: {} slot(s) filled this tick, more mailboxes are due", node, dispatched);
    } else {
      LOG.debug("Dispatched {} of {} due mailbox(es) on node {}", dispatched, due.size(), node);
    }
    return dispatched;
  }

  /**
   * One claimed mailbox, on an executor thread: the synchronization, and the
   * release of the claim whatever the synchronization did. {@link ContainerTransactional}
   * because a pool thread has no container bound, and the release is a database
   * write that needs one as much as the sync does.
   * <p>
   * The release is in a {@code finally} and nothing may move it: a claim that
   * outlives its run locks the mailbox out of every node for the whole stale
   * timeout, an hour of silence the owner reads as a broken mailbox. An orphan row
   * (the mailbox is bound to no connector any more) is dropped instead; the release
   * then finds nothing to release, which is fine.
   *
   * @param userId the mailbox owner
   * @param startedAt the claim's stamp, which becomes the mailbox's last sync
   */
  @ContainerTransactional
  public void runClaimed(String userId, Date startedAt) {
    try {
      emailBoxService.synchronizeClaimed(userId);
    } catch (IllegalAccessException e) {
      LOG.info("The mailbox of user {} is bound to no connector any more; dropping its sync state", userId);
      emailSyncStateStorage.delete(userId);
    } catch (RuntimeException | LinkageError e) {
      // The synchronization reports its own failures through the mailbox's status;
      // what reaches here is a failure of the machinery around it, and it must not
      // take the thread, nor the claim, with it.
      LOG.warn("The dispatched synchronization of user {}'s mailbox failed", userId, e);
    } finally {
      emailSyncStateStorage.release(userId, EmailConnectorUtils.getSyncNodeName(), startedAt);
    }
  }

  /**
   * A snapshot for the administration drawer's status line.
   *
   * @return the dispatcher's state on this node and across the cluster
   */
  public EmailSyncExecutorStatus getStatus() {
    Date now = new Date();
    Date staleBefore = EmailConnectorUtils.getSyncClaimStaleBefore(now);
    Date activeDueBefore = activeDueBefore(now);
    Date activeSince = activeSince();
    long dueBacklog = emailSyncStateStorage.countDue(activeSince, activeDueBefore, activeDueBefore, staleBefore);
    Date oldestDue = dueBacklog == 0 ? null
                                     : emailSyncStateStorage.findOldestDue(activeSince, activeDueBefore, activeDueBefore, staleBefore);
    long oldestDueMinutes = oldestDue == null ? 0 : Math.max(0, (now.getTime() - oldestDue.getTime()) / 60000L);
    return new EmailSyncExecutorStatus(EmailConnectorUtils.getSyncNodeName(),
                                       executor.getActiveCount(),
                                       executor.getQueue().size(),
                                       executor.getCorePoolSize(),
                                       emailSyncStateStorage.countClaimed(staleBefore),
                                       dueBacklog,
                                       oldestDueMinutes,
                                       emailSyncStateStorage.count());
  }

  /**
   * Stops the executor with the Spring context. The threads are daemons, so a JVM
   * shutdown never needed this; a context reload (a redeploy, a test suite building
   * several contexts) does, or each reload leaves its pool behind pointing at a dead
   * container. A run interrupted here leaves its claim to go stale, or to this
   * node's own recovery at its next boot, whichever comes first.
   */
  @PreDestroy
  public void shutdown() {
    executor.shutdownNow();
  }

  /**
   * What a restarted node does once, on its first tick: release the claims it
   * left behind (its half-done mailboxes are due again at once rather than in an
   * hour) and make sure every connected mailbox has a row.
   *
   * @param node this node's identity
   * @param now the tick's instant
   */
  private void recover(String node, Date now) {
    int released = emailSyncStateStorage.releaseClaimsOf(node);
    if (released > 0) {
      LOG.info("Released {} mailbox sync claim(s) left behind by node {}", released, node);
    }
    reconcileRows(now);
  }

  /**
   * A row for every connected mailbox, which is also the upgrade migration from
   * the per-user Quartz jobs: the connected users are the same list the old startup
   * registration walked, and each one missing a row gets one with the cadence
   * their settings remember, so nobody's mailbox is re-downloaded or demoted by the
   * upgrade. A row that exists is stamped active and otherwise left alone: its
   * claim columns may be another node's, and its cadence is already the truth.
   * Users whose settings are stored but bound to no connector (a disconnect keeps
   * the settings entry) get no row.
   *
   * @param now the tick's instant, the activity stamp of every reconciled row
   */
  private void reconcileRows(Date now) {
    List<Context> contexts =
                           settingService.getContextsByTypeAndScopeAndSettingName(Context.USER.getName(),
                                                                                  Scope.APPLICATION.getName(),
                                                                                  EmailConnectorService.EMAIL_CONNECTOR_SCOPE_ID,
                                                                                  EmailConnectorService.USER_EMAIL_SETTING_KEY,
                                                                                  0,
                                                                                  Integer.MAX_VALUE);
    int created = 0;
    for (Context context : contexts) {
      String userId = context.getId();
      try {
        EmailSyncState state = emailSyncStateStorage.get(userId);
        if (state != null) {
          emailSyncStateStorage.touchActivity(userId, now, now);
          continue;
        }
        UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(userId);
        if (userEmailSetting.getEmailConnectorId() == null) {
          continue;
        }
        Long lastSyncStart = userEmailSetting.getLastEmailSyncStartDate();
        Date lastSyncDate = lastSyncStart == null || lastSyncStart <= 0 ? null : new Date(lastSyncStart);
        emailSyncStateStorage.upsert(userId, lastSyncDate, now);
        created++;
      } catch (RuntimeException | LinkageError e) {
        LOG.warn("Could not reconcile the sync state of user {}; their mailbox is registered again at the next connect",
                 userId,
                 e);
      }
    }
    if (created > 0) {
      LOG.info("Registered {} connected mailbox(es) with the sync dispatcher", created);
    }
  }

  /**
   * Sizes the pool to the administered thread count when it moved. Maximum before
   * core when growing and core before maximum when shrinking, because a
   * {@link ThreadPoolExecutor} refuses a core above its maximum at every step.
   */
  private void resizePoolIfChanged() {
    int threads = emailConnectorService.getEmailSyncThreads();
    if (threads < 1 || threads == executor.getCorePoolSize()) {
      return;
    }
    LOG.info("Mailbox sync executor resized from {} to {} thread(s)", executor.getCorePoolSize(), threads);
    if (threads > executor.getMaximumPoolSize()) {
      executor.setMaximumPoolSize(threads);
      executor.setCorePoolSize(threads);
    } else {
      executor.setCorePoolSize(threads);
      executor.setMaximumPoolSize(threads);
    }
  }

  /**
   * How many mailboxes can be handed to the executor right now without one of them
   * waiting for a thread: the threads not busy, minus what already waits, and never
   * more than the executor would accept -- a thread not yet started or a queue
   * slot. Both are read because the queue keeps its boot capacity while the pool
   * follows the administered size.
   *
   * @return the free slots, zero or more
   */
  private int freeSlots() {
    int threads = executor.getCorePoolSize();
    int active = executor.getActiveCount();
    int queued = executor.getQueue().size();
    int runnable = threads - active - queued;
    int acceptable = Math.max(0, threads - executor.getPoolSize()) + executor.getQueue().remainingCapacity();
    return Math.max(0, Math.min(runnable, acceptable));
  }

  /**
   * The instant before which an active mailbox's last sync makes it due: now minus
   * the administered period, read at each call.
   *
   * @param now the reference instant
   * @return the threshold
   */
  private Date activeDueBefore(Date now) {
    return new Date(now.getTime() - emailConnectorService.getEmailBoxSyncPeriod() * 60000L);
  }

  /**
   * The instant at or after which an activity stamp makes a mailbox's owner active.
   * The epoch, for now: everyone is active until the tiering task sets a threshold.
   *
   * @return the activity threshold
   */
  private Date activeSince() {
    return new Date(0);
  }

  /**
   * The executor's boot size: the JVM property, since the stored setting is not
   * readable while the bean is built.
   *
   * @return the thread count, at least one
   */
  private static int defaultThreads() {
    return Math.max(1, Integer.parseInt(System.getProperty("email.connector.sync.threads", "10")));
  }

  /**
   * A bounded pool of daemon threads named {@code email-sync-N}, core and maximum
   * alike so its size is the administered one and nothing else, with a queue of the
   * same depth.
   *
   * @param threads the pool size
   * @return the executor
   */
  private ThreadPoolExecutor newExecutor(int threads) {
    return new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(threads), runnable -> {
      Thread thread = new Thread(runnable, THREAD_PREFIX + threadNumber.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    });
  }
}
