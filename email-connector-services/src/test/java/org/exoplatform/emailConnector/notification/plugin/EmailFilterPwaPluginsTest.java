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
package org.exoplatform.emailConnector.notification.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.emailConnector.utils.NotificationConstants;

import io.meeds.pwa.model.PwaNotificationMessage;
import io.meeds.pwa.plugin.PwaNotificationPlugin;

/**
 * The push twins of the mail filters' notifications (EXO-90668) send the sentence the
 * web plugin wrote as plain text: a push shows no markup, so the tags the web sentence
 * carries are dropped and their text kept.
 */
class EmailFilterPwaPluginsTest {

  /**
   * The assistant's notification's push: the same.
   */
  @Test
  void theAssistantsPushBodyIsPlainText() {
    assertPlainBody(new EmailFilterNotificationPwaPlugin());
  }

  /**
   * Renders a notification whose sentence carries markup through a push plugin.
   *
   * @param plugin the plugin
   */
  private static void assertPlainBody(PwaNotificationPlugin plugin) {
    NotificationInfo notification = NotificationInfo.instance()
                                                    .with(NotificationConstants.TITLE, "Mail assistant")
                                                    .with(NotificationConstants.CONTENT, "<strong>3</strong> suggestions <em>waiting</em>")
                                                    .with(NotificationConstants.LINK, "/portal/dw/mail");

    PwaNotificationMessage message = plugin.process(notification, null);

    assertEquals("3 suggestions waiting", message.getBody());
    assertEquals("Mail assistant", message.getTitle());
    assertEquals("/portal/dw/mail", message.getUrl());
  }
}
