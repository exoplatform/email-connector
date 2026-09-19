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

import org.exoplatform.emailConnector.service.EmailScheduledSendService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.common.ContainerTransactional;

/**
 * The tick that sends the mails scheduled for now (EXO-90434), and glue that must stay
 * glue: it holds no logic of its own and hands the whole pass to
 * {@link EmailScheduledSendService#dispatchDue()}.
 * <p>
 * A Spring {@code @Scheduled} method is node-local on this platform and fires on every
 * node of a cluster at once; what makes that correct is the claim the service takes in
 * the database before it sends anything, not anything here. Setting the cron to
 * {@code -} turns the dispatcher off (scheduled mails then wait, and "send now" still
 * works).
 */
@Component
public class EmailScheduledSendJob {

  private static final Log          LOG = ExoLogger.getLogger(EmailScheduledSendJob.class);

  @Autowired
  private EmailScheduledSendService emailScheduledSendService;

  /**
   * Dispatches the due scheduled mails, once a minute by default.
   * {@code @ContainerTransactional} because a scheduler thread has no container bound
   * and this one establishes it (see {@code EmailSyncDispatcher} for why it is not the
   * legacy {@code @ExoTransactional}). Nothing thrown escapes: a failed pass is this
   * job's to report, and the next tick tries again.
   */
  @Scheduled(cron = "${email.connector.scheduledSend.cron:0 * * * * ?}")
  @ContainerTransactional
  public void tick() {
    try {
      int dispatched = emailScheduledSendService.dispatchDue();
      if (dispatched > 0) {
        LOG.info("Dispatched {} scheduled mail(s)", dispatched);
      }
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("The scheduled-send dispatch failed; the next tick tries again", e);
    }
  }
}
