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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.event.EmailBoxSyncEvent;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.service.EmailSyncService;

/**
 * The connect listener is glue: it registers the mailbox with the dispatcher and
 * decides nothing itself.
 */
@ExtendWith(MockitoExtension.class)
public class EmailBoxSyncListenerTest {

  private static final String   USERNAME = "root";

  @Mock
  private EmailBoxService       emailBoxService;

  @Mock
  private EmailSyncService      emailSyncService;

  @InjectMocks
  private EmailBoxSyncListener  listener;

  @Test
  void aConnectingMailboxIsRegisteredWithTheDispatcher() {
    listener.handleEmailBoxSync(new EmailBoxSyncEvent(USERNAME));

    verify(emailBoxService).registerMailboxForSync(USERNAME);
  }

  /**
   * Registering only writes the sync-state row, which marks the mailbox due; the
   * fetch would then wait for the dispatcher's next tick, up to a whole minute of
   * an empty drawer. The per-user Quartz job this replaced was created with a null
   * start time -- read as now -- so it fired at once and repeated after that. The
   * rework kept the repeat and dropped the first fire; this pins it back on
   * (EXO-90060).
   */
  @Test
  void aConnectingMailboxIsSynchronizedAtOnceNotAtTheNextTick() {
    listener.handleEmailBoxSync(new EmailBoxSyncEvent(USERNAME));

    InOrder inOrder = inOrder(emailBoxService, emailSyncService);
    inOrder.verify(emailBoxService).registerMailboxForSync(USERNAME);
    inOrder.verify(emailSyncService).dispatchNow(USERNAME);
  }

  /**
   * The immediate sync is an optimisation over waiting for the tick, never a
   * reason to fail the connect: whatever it throws, the row is already written and
   * the tick will take the mailbox.
   */
  @Test
  void aFailedImmediateSyncIsLoggedNotThrown() {
    doThrow(new RuntimeException("executor is away")).when(emailSyncService).dispatchNow(USERNAME);

    assertDoesNotThrow(() -> listener.handleEmailBoxSync(new EmailBoxSyncEvent(USERNAME)));

    verify(emailBoxService).registerMailboxForSync(USERNAME);
  }

  @Test
  void aFailedRegistrationIsLoggedNotThrown() {
    // The boot reconciliation registers what this missed; the connect that raised
    // the event has already committed and must not be reported as failed.
    doThrow(new RuntimeException("database is away")).when(emailBoxService).registerMailboxForSync(USERNAME);

    assertDoesNotThrow(() -> listener.handleEmailBoxSync(new EmailBoxSyncEvent(USERNAME)));
  }
}
