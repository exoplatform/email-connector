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

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.event.EmailNotificationPreferencesChangedEvent;
import org.exoplatform.emailConnector.service.EmailBoxService;

/**
 * Glue only: a saved notification preference reaches the unread-count funnel,
 * for the user who saved it.
 */
@ExtendWith(MockitoExtension.class)
class EmailNotificationPreferencesListenerTest {

  private static final String                  USERNAME = "testuser";

  @Mock
  private EmailBoxService                      emailBoxService;

  @InjectMocks
  private EmailNotificationPreferencesListener listener;

  @Test
  void aSavedPreferenceReachesTheUnreadCountFunnel() {
    listener.handleNotificationPreferencesChanged(new EmailNotificationPreferencesChangedEvent(USERNAME));

    verify(emailBoxService).broadcastUnreadCountChanged(USERNAME);
  }
}
