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

import org.junit.jupiter.api.Test;

import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.emailConnector.utils.NotificationConstants;

import io.meeds.pwa.model.PwaNotificationMessage;

/**
 * The push twin of the web plugin: it only renders what the web plugin already wrote in
 * the receiver's language, it never builds its own sentence.
 */
public class EmailFilterNotificationPwaPluginTest {

  private final EmailFilterNotificationPwaPlugin plugin = new EmailFilterNotificationPwaPlugin();

  /**
   * The push message carries the title, the sentence and the link the web plugin wrote,
   * unchanged; the locale it is handed is never read.
   */
  @Test
  void processRendersWhatTheWebPluginAlreadyWrote() {
    NotificationInfo notification = NotificationInfo.instance()
                                                     .setFrom("")
                                                     .to("ben")
                                                     .with(NotificationConstants.TITLE, "Mail filter")
                                                     .with(NotificationConstants.CONTENT, "Acme matched 3 new mails")
                                                     .with(NotificationConstants.LINK, "/portal/dw?openEmailBox=true&mailRemoteId=42")
                                                     .end();

    PwaNotificationMessage message = plugin.process(notification, null);

    assertEquals("Mail filter", message.getTitle());
    assertEquals("Acme matched 3 new mails", message.getBody());
    assertEquals("/portal/dw?openEmailBox=true&mailRemoteId=42", message.getUrl());
  }

  /**
   * The plugin's id is the web plugin's own, so the push channel renders the same
   * notification the web channel already built.
   */
  @Test
  void theIdIsTheWebPluginsOwn() {
    assertEquals(NotificationConstants.EMAIL_FILTER_NOTIFICATION_PLUGIN, plugin.getId());
  }
}
