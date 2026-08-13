/**
 * This file is part of the Meeds project (https://meeds.io/).
 *
 * Copyright (C) 2020 - 2026 Meeds Association contact@meeds.io
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.exoplatform.emailConnector.listener;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.emailConnector.plugin.EmailApplicationBadgePlugin;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.ListenerService;

import io.meeds.appcenter.service.ApplicationBadgeService;

/**
 * Glue only: the listener turns the mailbox's own "unread count changed" event
 * into a badge invalidation, and must never register nor fire when the
 * Application Center is absent.
 */
@ExtendWith(MockitoExtension.class)
class EmailApplicationBadgeListenerTest {

  private static final String           USERNAME = "testuser";

  @Mock
  private ApplicationBadgeService       applicationBadgeService;

  @Mock
  private ListenerService               listenerService;

  @InjectMocks
  private EmailApplicationBadgeListener listener;

  @Test
  void refreshesTheBadgeOfTheMailboxOwner() throws Exception {
    listener.onEvent(new Event<>("any", USERNAME, null));

    verify(applicationBadgeService).updateBadge(EmailApplicationBadgePlugin.BADGE_NAME, USERNAME);
  }

  @Test
  void ignoresABlankUsername() throws Exception {
    listener.onEvent(new Event<>("any", "  ", null));

    verify(applicationBadgeService, never()).updateBadge(anyString(), anyString());
  }

  @Test
  void doesNotRegisterWithoutTheApplicationCenter() {
    ReflectionTestUtils.setField(listener, "applicationBadgeService", null);

    assertDoesNotThrow(() -> listener.init());

    // Registering would mean firing into a service that does not exist
    verifyNoInteractions(listenerService);
  }

}
