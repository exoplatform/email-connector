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

import org.exoplatform.emailConnector.utils.NotificationConstants;

/**
 * The push twin of {@link DelegatedNewEmailsNotificationPlugin} (EXO-90553): the same
 * title, sentence and shared-mailbox link, on the device.
 */
public class DelegatedNewEmailsNotificationPwaPlugin extends BaseEmailDelegationNotificationPwaPlugin {

  /**
   * @return the plugin id this twin renders for
   */
  @Override
  public String getId() {
    return NotificationConstants.DELEGATED_NEW_EMAILS_NOTIFICATION_PLUGIN;
  }
}
