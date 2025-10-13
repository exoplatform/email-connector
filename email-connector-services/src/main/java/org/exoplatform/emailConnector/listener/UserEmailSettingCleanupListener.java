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
package org.exoplatform.emailConnector.listener;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.exoplatform.emailConnector.event.UserEmailSettingCleanupEvent;
import org.exoplatform.emailConnector.service.UserEmailSettingService;

@Component
public class UserEmailSettingCleanupListener {

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void handleUserEmailSettingCleanup(UserEmailSettingCleanupEvent event) {
    List<String> users = userEmailSettingService.getUserEmailSettingsByEmailConnectorId(event.getEmailConnectorId());
    for (String user : users) {
      userEmailSettingService.deleteUserEmailSetting(user);
    }
  }
}