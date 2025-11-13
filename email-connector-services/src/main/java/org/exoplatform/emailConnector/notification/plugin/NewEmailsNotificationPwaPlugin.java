/**
 * Copyright (C) 2025 eXo Platform SAS
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

import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.resources.LocaleConfig;

import io.meeds.pwa.model.PwaNotificationMessage;
import io.meeds.pwa.plugin.PwaNotificationPlugin;

public class NewEmailsNotificationPwaPlugin implements PwaNotificationPlugin {

  @Override
  public PwaNotificationMessage process(NotificationInfo notification, LocaleConfig localeConfig) {
    PwaNotificationMessage notificationMessage = new PwaNotificationMessage();
    notificationMessage.setTitle(notification.getValueOwnerParameter(NotificationConstants.TITLE));
    notificationMessage.setBody(notification.getValueOwnerParameter(NotificationConstants.CONTENT));
    notificationMessage.setUrl(notification.getValueOwnerParameter(NotificationConstants.LINK));
    return notificationMessage;
  }

  @Override
  public String getId() {
    return NotificationConstants.NEW_EMAILS_NOTIFICATION_PLUGIN;
  }
}
