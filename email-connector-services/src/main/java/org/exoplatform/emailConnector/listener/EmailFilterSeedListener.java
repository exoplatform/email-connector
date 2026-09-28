/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.listener;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.emailConnector.event.EmailBoxSyncEvent;
import org.exoplatform.emailConnector.service.EmailFilterService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Glue between a mailbox connection and the owner's mail filters, with no logic of its
 * own: once the settings write that connected the mailbox is committed, the owner gets
 * their "Important mail" rule ({@link EmailFilterService#ensureImportantFilter}). A
 * failure is logged and swallowed: the first read of the owner's rules seeds it too.
 */
@Component
public class EmailFilterSeedListener {

  /** Where a seed that failed is reported. */
  private static final Log   LOG = ExoLogger.getLogger(EmailFilterSeedListener.class);

  /** The rules service that makes the seed. */
  @Autowired
  private EmailFilterService emailFilterService;

  /**
   * Seeds the "Important mail" rule of the owner whose mailbox was just connected.
   *
   * @param event the connect marker, carrying the mailbox owner
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onMailboxConnected(EmailBoxSyncEvent event) {
    try {
      emailFilterService.ensureImportantFilter(event.getUsername());
    } catch (Exception e) {
      LOG.warn("The Important mail filter of user {} could not be seeded on connection; the first read of the filters will",
               event.getUsername(),
               e);
    }
  }
}
