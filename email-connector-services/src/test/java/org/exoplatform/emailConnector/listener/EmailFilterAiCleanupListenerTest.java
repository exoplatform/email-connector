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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.emailConnector.event.EmailBoxCleanupEvent;
import org.exoplatform.emailConnector.service.EmailFilterService;

/**
 * A disconnected or rebound mailbox is told to the AI side of the mail filters, which
 * revokes the rules' standing approvals (EXO-90956), once the disconnection is written.
 */
@ExtendWith(MockitoExtension.class)
class EmailFilterAiCleanupListenerTest {

  @Mock
  private EmailFilterService           emailFilterService;

  @InjectMocks
  private EmailFilterAiCleanupListener listener;

  /**
   * The event tells the service of the user's disconnected mailbox. Mutant: the call
   * removed.
   */
  @Test
  void aDisconnectedMailboxIsTold() {
    listener.handleEmailBoxCleanup(new EmailBoxCleanupEvent("alice"));

    verify(emailFilterService).onMailboxDisconnected("alice");
  }

  /**
   * The framework calls the handler: once the disconnection commits, and on a rebind,
   * which has no transaction. Mutant: the annotation removed, which no call by hand can
   * kill, so the annotation is read here.
   *
   * @throws Exception never
   */
  @Test
  void theHandlerIsWiredAfterCommitWithAFallback() throws Exception {
    TransactionalEventListener wiring = EmailFilterAiCleanupListener.class.getMethod("handleEmailBoxCleanup", EmailBoxCleanupEvent.class)
                                                                          .getAnnotation(TransactionalEventListener.class);
    assertNotNull(wiring, "the framework calls it");
    assertEquals(TransactionPhase.AFTER_COMMIT, wiring.phase());
    assertTrue(wiring.fallbackExecution(), "a rebind has no transaction");
  }
}
