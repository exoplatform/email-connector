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

import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.resources.LocaleConfig;

import io.meeds.pwa.model.PwaNotificationMessage;
import io.meeds.pwa.plugin.PwaNotificationPlugin;

/**
 * The push twin of {@link EmailFilterNotificationPlugin}: it renders what the web plugin
 * already wrote in the receiver's language.
 */
public class EmailFilterNotificationPwaPlugin implements PwaNotificationPlugin {

  /**
   * The push message: the title, the sentence, the link to the mailbox.
   *
   * @param notification the notification
   * @param localeConfig the receiver's locale, unused: the sentence is already theirs
   * @return the message
   */
  @Override
  public PwaNotificationMessage process(NotificationInfo notification, LocaleConfig localeConfig) {
    PwaNotificationMessage notificationMessage = new PwaNotificationMessage();
    notificationMessage.setTitle(notification.getValueOwnerParameter(NotificationConstants.TITLE));
    notificationMessage.setBody(notification.getValueOwnerParameter(NotificationConstants.CONTENT));
    notificationMessage.setUrl(notification.getValueOwnerParameter(NotificationConstants.LINK));
    return notificationMessage;
  }

  /**
   * The plugin's id, the web plugin's.
   *
   * @return the id
   */
  @Override
  public String getId() {
    return NotificationConstants.EMAIL_FILTER_NOTIFICATION_PLUGIN;
  }
}
