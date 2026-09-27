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
package org.exoplatform.emailConnector.notification.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import org.exoplatform.emailConnector.utils.EmailConnectorUtils;

/**
 * A forwarding notification's link opens the forwarding drawer, not the mailbox's list.
 */
public class EmailForwardingNotificationPluginTest {

  /**
   * The mailbox's deep link, asking for the forwarding drawer.
   */
  @Test
  void theLinkOpensTheForwardingDrawer() {
    try (MockedStatic<EmailConnectorUtils> utils = mockStatic(EmailConnectorUtils.class)) {
      utils.when(() -> EmailConnectorUtils.getEmailsLink(anyString())).thenReturn("/portal/dw?openEmailBox=true");

      assertEquals("/portal/dw?openEmailBox=true&forwarding=true", EmailForwardingNotificationPlugin.link("ben"));
    }
  }
}
