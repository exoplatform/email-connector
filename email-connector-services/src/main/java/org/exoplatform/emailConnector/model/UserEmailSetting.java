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
package org.exoplatform.emailConnector.model;

import org.exoplatform.emailConnector.entity.UserEmailSettingEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class UserEmailSetting extends UserEmailSettingEntity {

  private String emailConnectorImageUrl;

  private String emailConnectorIcon;

  public UserEmailSetting(String emailConnectorId,
                          String emailAddress,
                          String emailPassword,
                          String emailBoxUserSyncPeriod,
                          SyncStatus emailSyncStatus,
                          int emailSyncFailedAttemps,
                          String emailConnectorImageUrl,
                          String emailConnectorIcon) {
    super(emailConnectorId, emailAddress, emailPassword, emailBoxUserSyncPeriod, emailSyncStatus, emailSyncFailedAttemps);
    this.emailConnectorImageUrl = emailConnectorImageUrl;
    this.emailConnectorIcon = emailConnectorIcon;
  }

}
