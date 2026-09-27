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

import org.exoplatform.emailConnector.plugin.EmailFilterAgentHandler;
import org.exoplatform.emailConnector.service.EmailFilterService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.common.ContainerTransactional;

/**
 * Releases, once after boot, the mail filters' matches left waiting for an assistant that
 * nothing on this deployment runs any more -- the AI add-on removed, or its profile
 * switched off, while matches were queued. Glue, like {@link EmailSyncDispatcher}: the
 * work and its per-match guards are {@link EmailFilterService#releaseUnansweredAgentMatches}.
 * <p>
 * Once, on the first tick rather than in a {@code @PostConstruct}, for the reason
 * {@code EmailSyncService} recovers its claims on its first tick: a startup thread has no
 * portal container to write through, while a tick runs {@code @ContainerTransactional} on
 * a job thread. The flag is set only once a sweep completed, so a sweep that failed as a
 * whole (the database away) is tried again on the next tick; after that each tick is a
 * read of a volatile field. When an {@link EmailFilterAgentHandler} is present nothing is
 * waiting in vain, and the flag is simply set.
 * <p>
 * Every node of a cluster runs it; the service claims each match with a conditional
 * UPDATE before it runs its actions, so a match is released by one node only.
 */
@Component
public class EmailFilterAgentSweepJob {

  private static final Log   LOG = ExoLogger.getLogger(EmailFilterAgentSweepJob.class);

  @Autowired
  private EmailFilterService emailFilterService;

  // Set once a sweep completed, or when a handler is present: from then on, a tick does
  // nothing.
  private volatile boolean   swept;

  /**
   * Sweeps once, on the first tick after boot that completes.
   * <p>
   * <b>{@code @ContainerTransactional}, not the deprecated {@code @ExoTransactional}</b>,
   * for the reason {@link EmailSyncDispatcher#tick()} gives: this one establishes the
   * container a scheduler thread lacks, the legacy one throws without it.
   */
  @Scheduled(cron = "${email.connector.filters.agent.sweep.cron:30 * * * * ?}")
  @ContainerTransactional
  public void sweep() {
    if (swept) {
      return;
    }
    try {
      if (emailFilterService.isAgentHandled()) {
        swept = true;
        return;
      }
      int released = emailFilterService.releaseUnansweredAgentMatches();
      swept = true;
      LOG.info("No assistant handles the mail filters on this deployment: {} waiting match(es) released", released);
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("The release of the mail filters' waiting matches failed; the next tick tries again", e);
    }
  }

  /**
   * Whether the sweep is done, for the tests.
   *
   * @return true once a sweep completed or a handler was found
   */
  boolean isSwept() {
    return swept;
  }
}
