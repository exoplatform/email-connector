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
package org.exoplatform.emailConnector.listener;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.emailConnector.event.EmailBoxSyncPeriodChangedEvent;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Glue: when the administration-wide sync period changes, every connected
 * user's mailbox sync job is re-registered so the new period actually takes
 * effect. No business logic here — the reschedule itself lives in
 * {@link EmailBoxService#rescheduleAllSyncJobs()}.
 */
@Component
public class EmailBoxSyncPeriodChangedListener {

  private static final Log LOG = ExoLogger.getLogger(EmailBoxSyncPeriodChangedListener.class);

  @Autowired
  private EmailBoxService  emailBoxService;

  /**
   * Reschedules every connected user's sync job.
   * <p>
   * {@code fallbackExecution = true} on purpose: {@link org.exoplatform.emailConnector.service.EmailConnectorService#saveEmailBoxSyncPeriod}
   * is a plain {@code SettingService} write with no surrounding
   * {@code @Transactional}, so without this flag a
   * {@code @TransactionalEventListener} would neither run nor complain — the
   * exact trap {@code EmailBoxCleanupListenerTest} documents for this same
   * event mechanism.
   *
   * @param event the period-changed marker, carries no data
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void handleSyncPeriodChanged(EmailBoxSyncPeriodChangedEvent event) {
    emailBoxService.rescheduleAllSyncJobs();
  }
}
