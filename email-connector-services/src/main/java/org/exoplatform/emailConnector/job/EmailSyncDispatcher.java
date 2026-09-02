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
package org.exoplatform.emailConnector.job;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.service.EmailSyncService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.common.ContainerTransactional;

/**
 * The tick that keeps every connected mailbox synchronized: the same shape as
 * caldav-integration's {@code CaldavSyncSweepJob}, and, like it, glue that must
 * stay glue.
 * <p>
 * It replaces the per-user Quartz job the add-on used to register for each
 * connected mailbox (a thousand users, a thousand jobs and triggers in the
 * scheduler). The job holds no logic of its own: it hands the work to
 * {@link EmailSyncService}, which reads every bound it needs -- the pool size, the
 * period, the stale timeout -- at each run rather than holding them, so an
 * administrator changing a value in the drawer sees the next tick behave
 * differently, not the next restart.
 * <p>
 * A Spring {@code @Scheduled} method on this platform is node-local and fires on
 * every node of a cluster; what makes that correct is the claim the service takes
 * in the database before it synchronizes anything, not anything here.
 * <p>
 * Setting the cron to {@code -} turns the dispatcher off entirely, which is the
 * right answer for a tiny instance whose few users press "sync now" themselves.
 */
@Component
public class EmailSyncDispatcher {

  private static final Log LOG = ExoLogger.getLogger(EmailSyncDispatcher.class);

  @Autowired
  private EmailSyncService emailSyncService;

  /**
   * Dispatches the mailboxes due for a synchronization, once a minute.
   * <p>
   * <b>{@code @ContainerTransactional}, not the deprecated
   * {@code @ExoTransactional}.</b> They are not two spellings of one thing. The
   * legacy aspect <i>requires</i> a container already bound to the thread and
   * throws when there is none; this one <i>establishes</i> it -- it reads the
   * current container, falls back to the portal container, and runs the request
   * lifecycle around the call. A scheduler thread is exactly the case with nothing
   * bound, which makes the legacy annotation the wrong one on a job by construction.
   * <p>
   * Nothing thrown by the service escapes: Spring would log and keep the schedule
   * anyway, but a tick that died half-way would otherwise be reported as an error
   * of the scheduler rather than of the mailbox it was working on.
   */
  @Scheduled(cron = "${email.connector.sync.tick.cron:0 * * * * ?}")
  @ContainerTransactional
  public void tick() {
    long start = System.currentTimeMillis();
    try {
      int dispatched = emailSyncService.dispatchDueSyncs();
      if (dispatched > 0) {
        LOG.info("Dispatched {} mailbox sync(s) in {} ms", dispatched, System.currentTimeMillis() - start);
      }
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("The mailbox sync dispatch failed; the next tick tries again", e);
    }
  }
}
