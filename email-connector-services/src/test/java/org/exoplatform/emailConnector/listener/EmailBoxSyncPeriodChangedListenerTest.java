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
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.emailConnector.event.EmailBoxSyncPeriodChangedEvent;
import org.exoplatform.emailConnector.service.EmailBoxService;

/**
 * What has to happen when an administrator changes the sync period — and that
 * the handler actually runs even though the write that triggers it opens no
 * transaction.
 */
@ExtendWith(MockitoExtension.class)
class EmailBoxSyncPeriodChangedListenerTest {

  @Mock
  private EmailBoxService                 emailBoxService;

  @InjectMocks
  private EmailBoxSyncPeriodChangedListener listener;

  @Test
  void aChangedPeriodReschedulesEveryConnectedUser() {
    listener.handleSyncPeriodChanged(new EmailBoxSyncPeriodChangedEvent());

    verify(emailBoxService).rescheduleAllSyncJobs();
  }

  /**
   * Pinned because the failure is silent otherwise:
   * {@code EmailConnectorService#saveEmailBoxSyncPeriod} is a plain
   * {@code SettingService} write, not wrapped in {@code @Transactional} — so
   * without {@code fallbackExecution = true} a {@code @TransactionalEventListener}
   * would neither run nor complain, and a saved period would silently never
   * reschedule anyone.
   */
  @Test
  void theHandlerRunsEvenWithoutATransaction() throws Exception {
    TransactionalEventListener annotation = EmailBoxSyncPeriodChangedListener.class
        .getMethod("handleSyncPeriodChanged", EmailBoxSyncPeriodChangedEvent.class)
        .getAnnotation(TransactionalEventListener.class);

    assertNotNull(annotation);
    assertTrue(annotation.fallbackExecution(), "the handler must run outside a transaction, or a period save never reschedules");
  }
}
