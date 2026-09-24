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
package org.exoplatform.emailConnector.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.entity.UserEmailSettingEntity;

import io.meeds.social.util.JsonUtils;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * EXO-90610. The model a response carries never writes the password; the entity the
 * settings storage serialises always does.
 */
class UserEmailSettingTest {

  private static final String PASSWORD = "s3cr3t-mail";

  /**
   * What Spring MVC serialises a response body with, under the platform's wire
   * contract (application-common.properties: a primitive absent from a body reads as
   * its default).
   */
  private static final JsonMapper RESPONSE_MAPPER = JsonMapper.builder()
                                                              .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                                                              .build();

  @Test
  void aResponseNeverWritesThePassword() {
    UserEmailSetting setting = setting();

    assertFalse(RESPONSE_MAPPER.writeValueAsString(setting).contains(PASSWORD));
    assertFalse(JsonUtils.toJsonString(setting).contains(PASSWORD));
    assertTrue(RESPONSE_MAPPER.writeValueAsString(setting).contains("user@example.invalid"));
  }

  @Test
  void aRequestBodyStillCarriesThePassword() {
    String body = "{\"emailConnectorId\":\"1\",\"emailAddress\":\"user@example.invalid\",\"emailPassword\":\"" + PASSWORD
        + "\"}";

    assertEquals(PASSWORD, RESPONSE_MAPPER.readValue(body, UserEmailSetting.class).getEmailPassword());
  }

  @Test
  void theStoredSettingKeepsThePasswordBothWays() {
    UserEmailSettingEntity entity = new UserEmailSettingEntity();
    entity.setEmailConnectorId("1");
    entity.setEmailAddress("user@example.invalid");
    entity.setEmailPassword(PASSWORD);

    String stored = JsonUtils.toJsonString(entity);

    assertTrue(stored.contains(PASSWORD), stored);
    assertEquals(PASSWORD, JsonUtils.fromJsonString(stored, UserEmailSetting.class).getEmailPassword());
  }

  @Test
  void neitherClassPrintsThePassword() {
    UserEmailSettingEntity entity = new UserEmailSettingEntity();
    entity.setEmailAddress("user@example.invalid");
    entity.setEmailPassword(PASSWORD);

    assertFalse(entity.toString().contains(PASSWORD), entity.toString());
    assertTrue(entity.toString().contains("user@example.invalid"), entity.toString());
    assertFalse(setting().toString().contains(PASSWORD), setting().toString());
  }

  private static UserEmailSetting setting() {
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailConnectorId("1");
    setting.setEmailAddress("user@example.invalid");
    setting.setEmailPassword(PASSWORD);
    return setting;
  }
}
