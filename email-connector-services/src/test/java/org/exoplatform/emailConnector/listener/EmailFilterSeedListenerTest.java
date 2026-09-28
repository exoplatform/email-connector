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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.event.EmailBoxSyncEvent;
import org.exoplatform.emailConnector.service.EmailFilterService;

/**
 * The glue between a mailbox connection and the owner's seeded "Important mail" rule:
 * it hands the owner over once the connection is committed, and a refusal never reaches
 * the connection.
 */
@ExtendWith(MockitoExtension.class)
public class EmailFilterSeedListenerTest {

  @Mock
  private EmailFilterService      emailFilterService;

  @InjectMocks
  private EmailFilterSeedListener listener;

  /**
   * The owner of the connected mailbox gets their seed.
   *
   * @throws Exception never
   */
  @Test
  void theConnectedOwnerGetsTheirSeed() throws Exception {
    listener.onMailboxConnected(new EmailBoxSyncEvent("alice"));

    verify(emailFilterService).ensureImportantFilter("alice");
  }

  /**
   * A seed that fails is logged, never thrown at the connection.
   *
   * @throws Exception never
   */
  @Test
  void aFailedSeedNeverReachesTheConnection() throws Exception {
    when(emailFilterService.ensureImportantFilter("alice")).thenThrow(new ObjectNotFoundException("emailConnector.filters.disabled"));

    assertDoesNotThrow(() -> listener.onMailboxConnected(new EmailBoxSyncEvent("alice")));
  }

  /**
   * Pins the wiring: after the connecting transaction committed, so the mailbox check
   * the seed makes reads the settings it wrote.
   *
   * @throws Exception never
   */
  @Test
  void theListenerRunsAfterTheConnectionIsCommitted() throws Exception {
    TransactionalEventListener wiring = EmailFilterSeedListener.class.getMethod("onMailboxConnected", EmailBoxSyncEvent.class)
                                                                     .getAnnotation(TransactionalEventListener.class);

    assertNotNull(wiring);
    assertEquals(TransactionPhase.AFTER_COMMIT, wiring.phase());
  }
}
