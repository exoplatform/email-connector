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

import org.exoplatform.emailConnector.event.ContactAuthoredEvent;
import org.exoplatform.emailConnector.service.EmailContactCardDavSyncService;

/**
 * Sends a contact the user just authored on to their address book, when they
 * asked for that to happen by itself.
 * <p>
 * Glue and nothing else, as every listener here is: whether the push happens is
 * {@code EmailContactCardDavSyncService#autoPublishContact}'s decision — two
 * switches and a bound book — and this class only carries the news across the
 * seam that keeps the contact store from knowing what CardDAV is.
 */
@Component
public class ContactAutoPublishListener {

  @Autowired
  private EmailContactCardDavSyncService emailContactCardDavSyncService;

  /**
   * Handles a contact a person authored through the form.
   * <p>
   * AFTER_COMMIT, unlike this package's release listeners: those undo a binding
   * and must run whatever happens next, while this one hands a card to a THIRD
   * PARTY. Publishing a contact whose local row then rolled back would put on
   * somebody's phone a person their address book here never kept — an
   * inconsistency no later sync can see, let alone repair. So the push waits
   * for the store to have actually committed.
   * <p>
   * {@code fallbackExecution = true} for the case with no transaction at all,
   * which is the ordinary one on this path: the create runs through the
   * storage's own transaction and the event is raised after it closed. Without
   * the fallback a transactional listener with no transaction in progress
   * neither runs nor complains, and the automatic push would simply never
   * happen — silently, and only in production.
   *
   * @param event the raised event
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void handleContactAuthored(ContactAuthoredEvent event) {
    emailContactCardDavSyncService.autoPublishContact(event.getUsername(), event.getContactId());
  }
}
