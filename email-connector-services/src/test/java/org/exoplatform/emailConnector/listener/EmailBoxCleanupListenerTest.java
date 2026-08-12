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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.emailConnector.event.EmailBoxCleanupEvent;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.service.EmailContactService;

/**
 * What has to happen when a mailbox is rebound or disconnected — and, just as
 * importantly, that this listener runs at all.
 */
@ExtendWith(MockitoExtension.class)
public class EmailBoxCleanupListenerTest {

  private static final String    USERNAME = "root";

  @Mock
  private EmailBoxService        emailBoxService;

  @Mock
  private EmailContactService    emailContactService;

  @InjectMocks
  private EmailBoxCleanupListener listener;

  @Test
  void aReboundMailboxDropsItsCache_reArmsCollection_andHandsOverItsContacts() {
    listener.handleEmailBoxCleanup(new EmailBoxCleanupEvent(USERNAME));

    verify(emailBoxService).deleteUserEmails(USERNAME);
    verify(emailContactService).resetCollectionBackfill(USERNAME);
    verify(emailContactService).releaseCollectedContacts(USERNAME);
  }

  @Test
  void theHandlerStillRunsWhenNothingOpenedATransaction() throws Exception {
    // Pinned because the failure is silent. A @TransactionalEventListener with no
    // transaction in progress neither runs nor complains, and REBINDING an account
    // is a plain settings write that opens none -- so before fallbackExecution this
    // handler was skipped on the very path it exists for, leaving the previous
    // mailbox's mail cached and collection un-armed for the new account.
    // Disconnecting is transactional and did run, which is what made it look fine.
    TransactionalEventListener annotation = EmailBoxCleanupListener.class
        .getMethod("handleEmailBoxCleanup", EmailBoxCleanupEvent.class)
        .getAnnotation(TransactionalEventListener.class);

    assertNotNull(annotation);
    assertTrue(annotation.fallbackExecution(), "the handler must run outside a transaction, or a rebind skips it");
  }

  @Test
  void theAddressBookReleaseRunsBeforeThisCleanup_byConstructionNotByLuck() throws Exception {
    // Both listeners handle the same EmailBoxCleanupEvent, and their relative
    // order is load-bearing: the release reads each address-book row's
    // seenCount to decide COLLECTED vs MANUAL, and demotes rows to COLLECTED
    // expecting THIS listener's collected-release to hand them over to MANUAL
    // (zeroing those very counters) in the same rebind. Before the @Order pair
    // the order held only by the accident of bean registration -- a class
    // rename could have flipped it silently. Read via reflection so removing
    // either annotation fails a test rather than a user.
    Order release = ContactBookReleaseListener.class
        .getMethod("handleEmailBoxCleanup", EmailBoxCleanupEvent.class)
        .getAnnotation(Order.class);
    Order cleanup = EmailBoxCleanupListener.class
        .getMethod("handleEmailBoxCleanup", EmailBoxCleanupEvent.class)
        .getAnnotation(Order.class);

    assertNotNull(release, "the release handler must carry an explicit @Order — the ordering may not be implicit");
    assertNotNull(cleanup, "the cleanup handler must carry an explicit @Order — the ordering may not be implicit");
    assertTrue(release.value() < cleanup.value(),
               "the address-book release must run before the cleanup's collected-release");
  }
}
