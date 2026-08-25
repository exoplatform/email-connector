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
package org.exoplatform.emailConnector.provider;

import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.UserEmailSettingService;
import org.exoplatform.services.connector.credentials.PersonalCredentialsSource;
import org.exoplatform.services.connector.credentials.RawCredentials;

/**
 * Exposes the email connector's own stored personal credentials
 * ({@link UserEmailSetting#getEmailAddress()}/{@link UserEmailSetting#getEmailPassword()})
 * to the generic {@link org.exoplatform.services.connector.credentials.PersonalCredentialsProvider}.
 */
@Component
public class EmailPersonalCredentialsSource implements PersonalCredentialsSource {

  public static final String CONNECTOR_KIND = "email";

  private final UserEmailSettingService userEmailSettingService;

  public EmailPersonalCredentialsSource(UserEmailSettingService userEmailSettingService) {
    this.userEmailSettingService = userEmailSettingService;
  }

  @Override
  public String getConnectorKind() {
    return CONNECTOR_KIND;
  }

  @Override
  public RawCredentials getCredentials(String username) {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null) {
      return null;
    }
    return new RawCredentials(userEmailSetting.getEmailAddress(), userEmailSetting.getEmailPassword());
  }

}
