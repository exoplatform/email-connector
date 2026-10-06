/*
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.emailConnector.event.EmailConnectorProviderChangedEvent;
import org.exoplatform.emailConnector.event.EmailManagedModeChangedEvent;
import org.exoplatform.emailConnector.service.EmailManagedDisconnectionService;

/**
 * Every disconnection an administrator's change causes starts in this listener, so a
 * listener that does not run disconnects nobody, and nothing says so.
 */
@ExtendWith(MockitoExtension.class)
class EmailManagedDisconnectionListenerTest {

  @Mock
  private EmailManagedDisconnectionService disconnectionService;

  @InjectMocks
  private EmailManagedDisconnectionListener listener;

  @Test
  void aManagedModeChangeDisconnectsTheUsersItNoLongerGoverns() {
    listener.handleManagedModeChanged(new EmailManagedModeChangedEvent());

    verify(disconnectionService).disconnectUsersNoLongerManaged();
  }

  @Test
  void aProviderChangeDisconnectsEveryUserOfTheConnector() {
    listener.handleProviderChanged(new EmailConnectorProviderChangedEvent(3L));

    verify(disconnectionService).disconnectAllUsersOf(3L);
  }

  /**
   * No Spring transaction is active on any path that publishes these events, and a
   * {@code @TransactionalEventListener} with no transaction runs only with
   * {@code fallbackExecution}: without it each handler is skipped, with a debug line.
   * Read by reflection, since the tests above call the handlers by hand. Killed by the
   * mutants that remove {@code fallbackExecution = true}, change the phase, or remove the
   * annotation, on either handler.
   */
  @Test
  void bothHandlersRunAfterTheCommitAndWithoutATransaction() throws Exception {
    List<Method> handlers = List.of(EmailManagedDisconnectionListener.class.getMethod("handleManagedModeChanged",
                                                                                      EmailManagedModeChangedEvent.class),
                                    EmailManagedDisconnectionListener.class.getMethod("handleProviderChanged",
                                                                                      EmailConnectorProviderChangedEvent.class));
    for (Method handler : handlers) {
      TransactionalEventListener annotation = handler.getAnnotation(TransactionalEventListener.class);
      assertNotNull(annotation, handler.getName() + " must be a transactional event listener");
      assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase(), handler.getName() + " must run after the commit");
      assertTrue(annotation.fallbackExecution(), handler.getName() + " must run when no transaction is active");
    }
  }
}
