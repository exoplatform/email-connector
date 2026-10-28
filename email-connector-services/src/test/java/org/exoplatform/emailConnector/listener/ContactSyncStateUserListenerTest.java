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

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.service.UserEmailSettingService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.impl.UserImpl;

/**
 * The glue between a user's deletion and their address-book sync state.
 */
@ExtendWith(MockitoExtension.class)
class ContactSyncStateUserListenerTest {

  @Mock
  private UserEmailSettingService      userEmailSettingService;

  @InjectMocks
  private ContactSyncStateUserListener listener;

  /**
   * The deleted user's state is cleared, and no one else's.
   */
  @Test
  void aDeletedUsersSyncStateIsCleared() {
    listener.postDelete(new UserImpl("alice"));

    verify(userEmailSettingService).clearContactSyncState("alice");
    verifyNoMoreInteractions(userEmailSettingService);
  }

  /**
   * A failure to clear never fails the deletion.
   */
  @Test
  void aFailureToClearDoesNotFailTheDeletion() {
    doThrow(new IllegalStateException("database down")).when(userEmailSettingService).clearContactSyncState("alice");

    listener.postDelete(new UserImpl("alice"));

    verify(userEmailSettingService).clearContactSyncState("alice");
  }

  /**
   * No user, nothing to clear.
   */
  @Test
  void noUserClearsNothing() {
    listener.postDelete((User) null);

    verifyNoInteractions(userEmailSettingService);
  }
}
