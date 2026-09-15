/**
 * Copyright (C) 2025 eXo Platform SAS
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

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The per-user Quartz sync job this add-on registered before the
 * {@link EmailSyncDispatcher} took its place: kept for one release as a no-op
 * shell, and only because of what a clustered instance holds in its job store.
 * <p>
 * Under the cluster profile the kernel's scheduler persists its jobs (JDBC job
 * store), by class NAME. Every {@code <user>EmailBoxSyncJob} still sits there
 * when the first boot of this version starts, and the kernel deletes them itself
 * in {@code JobSchedulerServiceImpl.start()} (a job not re-registered during boot
 * is "removed from the configuration" and dropped, triggers included). But the
 * scheduler starts before that cleanup, and a persisted trigger firing in that
 * window makes Quartz load this class: were it gone, every such trigger would
 * fail with a {@code JobPersistenceException}, be put in ERROR state and log an
 * error line -- one per connected user, on the first boot after the upgrade. An
 * empty class costs twenty lines and makes that window silent. Nothing registers
 * this job any more; standalone instances (RAM job store) never held it past a
 * restart.
 *
 * @deprecated since the dispatcher (EXO-89945); scheduled for deletion in the
 *             next release, once every clustered instance has booted this one at
 *             least once and the persisted jobs are gone.
 */
@Deprecated
@DisallowConcurrentExecution
public class EmailBoxSyncJob implements Job {

  private static final Log LOG = ExoLogger.getLogger(EmailBoxSyncJob.class);

  /**
   * Does nothing: the mailbox this job named is synchronized by the dispatcher
   * now. One DEBUG line, so a persisted trigger firing before the kernel's
   * cleanup leaves a trace for whoever wonders, and nothing else.
   *
   * @param context the Quartz execution context of the persisted job
   * @throws JobExecutionException never
   */
  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    LOG.debug("Ignoring the persisted legacy sync job {}; mailboxes are synchronized by the dispatcher now",
              context.getJobDetail().getKey());
  }
}
