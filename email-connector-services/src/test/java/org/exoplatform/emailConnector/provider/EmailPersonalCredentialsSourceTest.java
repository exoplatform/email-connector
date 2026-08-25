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
package org.exoplatform.emailConnector.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.UserEmailSettingService;
import org.exoplatform.services.connector.credentials.RawCredentials;

@ExtendWith(MockitoExtension.class)
public class EmailPersonalCredentialsSourceTest {

  private static final String           TEST_USER = "testuser";

  @Mock
  private UserEmailSettingService       userEmailSettingService;

  @InjectMocks
  private EmailPersonalCredentialsSource emailPersonalCredentialsSource;

  @Test
  public void testGetConnectorKind() {
    assertEquals("email", emailPersonalCredentialsSource.getConnectorKind());
  }

  @Test
  public void testGetCredentialsWhenConfigured() {
    UserEmailSetting userEmailSetting = new UserEmailSetting();
    userEmailSetting.setEmailConnectorId("1");
    userEmailSetting.setEmailAddress("user@example.com");
    userEmailSetting.setEmailPassword("secret");
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);

    RawCredentials credentials = emailPersonalCredentialsSource.getCredentials(TEST_USER);

    assertEquals("user@example.com", credentials.getUsername());
    assertEquals("secret", credentials.getSecret());
  }

  @Test
  public void testGetCredentialsWhenNotConfigured() {
    UserEmailSetting userEmailSetting = new UserEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);

    assertNull(emailPersonalCredentialsSource.getCredentials(TEST_USER));
  }

}
