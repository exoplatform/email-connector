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

import org.exoplatform.emailConnector.event.EmailBoxSyncEvent;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Glue: when a user connects or rebinds their mailbox ({@link EmailBoxSyncEvent}),
 * the mailbox is registered with the sync dispatcher so its first download runs
 * at the next tick. No business logic here; the registration itself lives in
 * {@link EmailBoxService#registerMailboxForSync(String)}. It used to register a
 * per-user Quartz job in the same place.
 */
@Component
public class EmailBoxSyncListener {

  private static final Log LOG = ExoLogger.getLogger(EmailBoxSyncListener.class);

  @Autowired
  private EmailBoxService  emailBoxService;

  /**
   * Registers the connected mailbox with the dispatcher, after the settings
   * write that announced it is committed. A failure is logged and swallowed: the
   * dispatcher's boot reconciliation registers any mailbox this missed.
   *
   * @param event the connect marker, carrying the mailbox owner
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handleEmailBoxSync(EmailBoxSyncEvent event) {
    try {
      emailBoxService.registerMailboxForSync(event.getUsername());
    } catch (Exception e) {
      LOG.warn("Error registering the mailbox of user {} for synchronization", event.getUsername(), e);
    }
  }
}
