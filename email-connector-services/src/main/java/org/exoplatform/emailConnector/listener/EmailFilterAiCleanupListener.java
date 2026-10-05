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
package org.exoplatform.emailConnector.listener;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.emailConnector.event.EmailBoxCleanupEvent;
import org.exoplatform.emailConnector.service.EmailFilterService;

/**
 * Tells the AI add-on's side of the mail filters that a mailbox was disconnected, or
 * bound to another account (EXO-90956): the standing approvals the user gave the rules
 * of the mailbox that has gone no longer hold. Once the disconnection is written, so a
 * rolled-back one revokes nothing.
 */
@Component
public class EmailFilterAiCleanupListener {

  @Autowired
  private EmailFilterService emailFilterService;

  /**
   * Handles a mailbox that has been rebound or disconnected.
   *
   * @param event the raised event
   */
  // fallbackExecution, as EmailBoxCleanupListener: a rebind is a settings write with no
  // transaction, and a transactional listener with none does not run otherwise.
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void handleEmailBoxCleanup(EmailBoxCleanupEvent event) {
    emailFilterService.onMailboxDisconnected(event.getUsername());
  }
}
