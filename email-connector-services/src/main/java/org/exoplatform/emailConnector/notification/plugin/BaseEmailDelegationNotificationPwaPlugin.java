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

import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.resources.LocaleConfig;

import io.meeds.pwa.model.PwaNotificationMessage;
import io.meeds.pwa.plugin.PwaNotificationPlugin;

/**
 * The push rendering the two mailbox-delegation notifications share (EXO-90503): the
 * title, the sentence and the link the web plugin already resolved in the receiver's
 * language, handed to the device as they are.
 * <p>
 * Nothing is rebuilt here on purpose. The web plugin ran when the event was raised,
 * with the receiver's locale; a second translation pass on the push side would be a
 * second place to keep the wording right, and the two would drift.
 */
public abstract class BaseEmailDelegationNotificationPwaPlugin implements PwaNotificationPlugin {

  /**
   * Turns the stored notification into the message pushed to the device.
   *
   * @param notification the notification as the plugin built it
   * @param localeConfig the receiver's locale configuration, unused: the sentence was
   *          already written in their language
   * @return the push message
   */
  @Override
  public PwaNotificationMessage process(NotificationInfo notification, LocaleConfig localeConfig) {
    PwaNotificationMessage message = new PwaNotificationMessage();
    // The title and the sentence are written as HTML for the web and mail channels (a
    // display name is escaped into them); a device shows text, so the entities are
    // decoded here rather than shown as "&#39;".
    message.setTitle(htmlToText(notification.getValueOwnerParameter(NotificationConstants.TITLE)));
    message.setBody(htmlToText(notification.getValueOwnerParameter(NotificationConstants.CONTENT)));
    message.setUrl(notification.getValueOwnerParameter(NotificationConstants.LINK));
    return message;
  }
}
