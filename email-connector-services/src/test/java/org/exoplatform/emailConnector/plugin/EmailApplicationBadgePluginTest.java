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
package org.exoplatform.emailConnector.plugin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.service.UserEmailSettingService;

import io.meeds.appcenter.service.ApplicationBadgePluginRegistry;

/**
 * The plugin is binding plus a count over the local mirror. What matters is
 * that it never keeps the mailbox from starting when App Center is absent, and
 * that it opts out users with no connected mailbox rather than probing them.
 */
@ExtendWith(MockitoExtension.class)
class EmailApplicationBadgePluginTest {

  private static final String            USERNAME = "testuser";

  @Mock
  private ApplicationBadgePluginRegistry registry;

  @Mock
  private EmailBoxService                emailBoxService;

  @Mock
  private UserEmailSettingService        userEmailSettingService;

  @InjectMocks
  private EmailApplicationBadgePlugin    plugin;

  @Test
  void nameIsTheStableBadgeIdentifier() {
    // Travels on the WebSocket frame and binds the catalog entry: it must not
    // change once released
    assertEquals("emailUnread", plugin.getName());
  }

  @Test
  void countDelegatesToTheLocalMirror() {
    when(emailBoxService.countUnreadEmails(USERNAME)).thenReturn(4L);

    assertEquals(4L, plugin.countBadge(USERNAME));
  }

  @Test
  void countReturnsZeroWhenTheMirrorFails() {
    when(emailBoxService.countUnreadEmails(USERNAME)).thenThrow(new IllegalStateException("db down"));

    assertEquals(0L, plugin.countBadge(USERNAME));
  }

  @Test
  void isEnabledOnlyForAConnectedMailbox() {
    when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(new UserEmailSetting());

    assertFalse(plugin.isEnabled(USERNAME));
  }

  @Test
  void isNotSelfCachedSoAppCenterOwnsTheCaching() {
    assertFalse(plugin.isSelfCached());
  }

  @Test
  void registersItselfWhenTheRegistryIsPresent() {
    plugin.init();

    verify(registry).addPlugin(plugin);
  }

  @Test
  void startsWithoutTheApplicationCenterRegistry() {
    ReflectionTestUtils.setField(plugin, "applicationBadgePluginRegistry", null);

    // The badge is a nicety: a missing registry must not fail the mailbox
    assertDoesNotThrow(() -> plugin.init());
  }

  @Test
  void bindsToTheDrawerShippedInApplicationsJson() {
    ReflectionTestUtils.setField(plugin, "drawerNames", List.of("email"));

    assertTrue(plugin.getDrawerNames().contains("email"));
  }

}
