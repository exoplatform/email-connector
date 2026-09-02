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
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.event.EmailBoxSyncEvent;
import org.exoplatform.emailConnector.service.EmailBoxService;

/**
 * The connect listener is glue: it registers the mailbox with the dispatcher and
 * decides nothing itself.
 */
@ExtendWith(MockitoExtension.class)
public class EmailBoxSyncListenerTest {

  private static final String   USERNAME = "root";

  @Mock
  private EmailBoxService       emailBoxService;

  @InjectMocks
  private EmailBoxSyncListener  listener;

  @Test
  void aConnectingMailboxIsRegisteredWithTheDispatcher() {
    listener.handleEmailBoxSync(new EmailBoxSyncEvent(USERNAME));

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
