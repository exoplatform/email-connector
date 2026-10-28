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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.service.UserEmailSettingService;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserHandler;
import org.exoplatform.services.organization.impl.UserImpl;

import jakarta.annotation.PostConstruct;

/**
 * The glue between a user's deletion and their address-book sync state.
 */
@ExtendWith(MockitoExtension.class)
class ContactSyncStateUserListenerTest {

  @Mock
  private OrganizationService          organizationService;

  @Mock
  private UserHandler                  userHandler;

  @Mock
  private UserEmailSettingService      userEmailSettingService;

  @InjectMocks
  private ContactSyncStateUserListener listener;

  /**
   * A listener nobody registers clears nothing: the registration is what makes
   * the platform call it when a user is deleted.
   */
  @Test
  void registersItselfForUserEvents() throws Exception {
    when(organizationService.getUserHandler()).thenReturn(userHandler);

    listener.init();

    verify(userHandler).addUserEventListener(listener);
    // The container calls init: without the annotation the listener is never registered.
    assertTrue(ContactSyncStateUserListener.class.getMethod("init").isAnnotationPresent(PostConstruct.class));
  }

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
   * A failure to clear does not escape the listener. The platform would catch it
   * too, so the deletion goes on either way.
   */
  @Test
  void aFailureToClearDoesNotEscapeTheListener() {
    doThrow(new IllegalStateException("database down")).when(userEmailSettingService).clearContactSyncState("alice");

    listener.postDelete(new UserImpl("alice"));

    verify(userEmailSettingService).clearContactSyncState("alice");
  }

  /**
   * A new account starts from no state, even one a run wrote back after an
   * earlier account with the same username was deleted.
   */
  @Test
  void aCreatedUsersSyncStateIsCleared() {
    listener.postSave(new UserImpl("alice"), true);

    verify(userEmailSettingService).clearContactSyncState("alice");
    verifyNoMoreInteractions(userEmailSettingService);
  }

  /**
   * Saving an existing user keeps their state.
   */
  @Test
  void anUpdatedUsersSyncStateIsKept() {
    listener.postSave(new UserImpl("alice"), false);

    verifyNoInteractions(userEmailSettingService);
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
