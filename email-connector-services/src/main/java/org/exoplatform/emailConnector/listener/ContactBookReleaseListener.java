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

import org.exoplatform.emailConnector.event.ContactBookReleaseEvent;
import org.exoplatform.emailConnector.event.EmailBoxCleanupEvent;
import org.exoplatform.emailConnector.service.EmailContactCardDavSyncService;

/**
 * Lets go of the contacts of an address book the user is no longer bound to.
 * <p>
 * Listens to both events that can end a binding: the mailbox one, raised when an
 * account is rebound or disconnected, and the address-book one, raised when only
 * the book is switched off.
 */
@Component
public class ContactBookReleaseListener {

  // Both handlers fall back to running outside a transaction. A transactional
  // listener with no transaction in progress does not run and does not complain,
  // and the binding is saved through a settings write that carries none -- so the
  // release was dropped in silence, which is the one failure mode this class
  // exists to prevent.

  @Autowired
  private EmailContactCardDavSyncService emailContactCardDavSyncService;

  /**
   * Handles a mailbox that has been rebound or disconnected.
   *
   * @param event the raised event
   */
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
  public void handleEmailBoxCleanup(EmailBoxCleanupEvent event) {
    emailContactCardDavSyncService.releaseUnboundBooks(event.getUsername());
  }

  /**
   * Handles an address book that has been switched off.
   *
   * @param event the raised event
   */
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
  public void handleContactBookRelease(ContactBookReleaseEvent event) {
    emailContactCardDavSyncService.releaseUnboundBooks(event.getUsername());
  }
}
