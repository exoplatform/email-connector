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
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.emailConnector.event.EmailBoxCleanupEvent;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.service.EmailContactService;

/**
 * Drops what a rebound or disconnected mailbox leaves behind: its cached
 * mail, its collection marker, and the "collected" claim on the contacts it
 * produced.
 */
@Component
public class EmailBoxCleanupListener {

  /**
   * Where this listener stands among the handlers of
   * {@link EmailBoxCleanupEvent} — deliberately AFTER
   * {@link ContactBookReleaseListener#RELEASE_ORDER}, and derived from it so
   * the relationship cannot drift: the address-book release must read each
   * row's {@code seenCount} and demote its rows to COLLECTED before this
   * handler's collected-release relabels COLLECTED rows to MANUAL and zeroes
   * those very counters. See the release constant's own comment for the full
   * story; this side only states that it comes second.
   */
  public static final int CLEANUP_ORDER = ContactBookReleaseListener.RELEASE_ORDER + 100;

  @Autowired
  private EmailBoxService     emailBoxService;

  @Autowired
  private EmailContactService emailContactService;

  /**
   * Handles a mailbox that has been rebound or disconnected, after the
   * address-book release has run — the {@code @Order} pair pins that.
   *
   * @param event the raised event
   */
  // fallbackExecution, for the reason ContactBookReleaseListener already documents:
  // a transactional listener with no transaction in progress does not run and does
  // not complain. Disconnecting an account is transactional, but REBINDING one is
  // not -- it is a settings write -- so this handler was silently skipped on the
  // very path that needs it most, leaving the previous mailbox's cached mail in
  // place and collection un-armed for the new account.
  @Order(CLEANUP_ORDER)
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
  public void handleEmailBoxCleanup(EmailBoxCleanupEvent event) {
    emailBoxService.deleteUserEmails(event.getUsername());
    // The cache the collection reads has just gone, so collection has to be able to
    // start from nothing again. Without this the next mailbox collects nobody: its
    // first sync caches the inbox before the sent folder, and an inbox sender is
    // judged against the organisations the user has written to.
    emailContactService.resetCollectionBackfill(event.getUsername());
    // The people collected from the mailbox that has just gone are now the user's
    // own: kept, editable, and no longer claiming a history this store cannot show.
    // On a rebind this includes the rows the address-book release just demoted --
    // which is exactly why it must have run first.
    emailContactService.releaseCollectedContacts(event.getUsername());
  }
}