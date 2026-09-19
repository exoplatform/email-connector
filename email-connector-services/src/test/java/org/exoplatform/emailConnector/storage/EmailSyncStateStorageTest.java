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
package org.exoplatform.emailConnector.storage;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.dao.EmailSyncStateDAO;
import org.exoplatform.emailConnector.entity.EmailSyncStateEntity;

/**
 * The creation of a mailbox's sync-state row (EXO-90418).
 */
@ExtendWith(MockitoExtension.class)
public class EmailSyncStateStorageTest {

  @Mock
  private EmailSyncStateDAO     emailSyncStateDAO;

  @InjectMocks
  private EmailSyncStateStorage storage;

  /**
   * A created row does not start at epoch 0: a disconnect or rebind deletes the row,
   * and a re-created row at 0 -- the value a never-reset mailbox's consumer cursor
   * already holds -- would read as "nothing changed" while the cache was rebuilt.
   * Seeded with the creation instant, a value no earlier row of the mailbox had.
   */
  @Test
  void aCreatedRowStartsAtAnEpochNoEarlierRowHad() {
    when(emailSyncStateDAO.existsById("alice")).thenReturn(false);
    long before = System.currentTimeMillis();

    storage.upsert("alice", null, null);

    ArgumentCaptor<EmailSyncStateEntity> created = ArgumentCaptor.forClass(EmailSyncStateEntity.class);
    verify(emailSyncStateDAO).saveAndFlush(created.capture());
    assertNotEquals(0L, created.getValue().getInboxEpoch());
    assertTrue(created.getValue().getInboxEpoch() >= before, "the creation instant");
  }
}
