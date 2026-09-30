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
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;

/**
 * What eXo's own records say about a user's automatic reply (EXO-90793): the last
 * summary eXo read or wrote, never the mail server; an unreadable one reads as on.
 */
@ExtendWith(MockitoExtension.class)
public class EmailAbsenceRecordsTest {

  private static final String USER = "mary";

  @Mock
  private SettingService      settingService;

  @InjectMocks
  private EmailAbsenceService service;

  /**
   * No summary, no reply; an "on" summary is one, an "off" one is not, an unreadable one
   * is.
   */
  @Test
  @SuppressWarnings({ "rawtypes", "unchecked" })
  public void theReplyIsOnWhenItsLastSummarySaysSo() {
    assertFalse(service.hasExoReply(USER));

    when(settingService.get(Context.USER.id(USER),
                            UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                            EmailAbsenceService.ABSENCE_SETTING_KEY)).thenReturn((SettingValue) SettingValue.create("{\"enabled\":true}"),
                                                                                 (SettingValue) SettingValue.create("{\"enabled\":false}"),
                                                                                 (SettingValue) SettingValue.create("{not json"));
    assertTrue(service.hasExoReply(USER));
    assertFalse(service.hasExoReply(USER));
    assertTrue(service.hasExoReply(USER), "an unreadable summary reads as on");
  }
}
