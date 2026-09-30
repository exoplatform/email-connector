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
package org.exoplatform.emailConnector.storage;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;

/**
 * The engines the connector administration screen chose for each connector, kept in
 * the global settings, which only the administration screen writes: no schema, one
 * entry per connector and kind of engine.
 */
@Component
public class ConnectorEngineChoiceStorage {

  /** The kind of the server rules engine. */
  public static final String RULES_ENGINE = "rulesEngine";

  /** The kind of the mailbox sharing engine. */
  public static final String ACL_ENGINE   = "aclEngine";

  /** The setting scope of the choices, in the {@code GLOBAL} context. */
  public static final Scope  SCOPE        = Scope.APPLICATION.id("EMAIL_CONNECTOR_ENGINES");

  @Autowired
  private SettingService     settingService;

  /**
   * The storage over the platform's settings.
   */
  public ConnectorEngineChoiceStorage() {
    // Spring injects the settings.
  }

  /**
   * The storage over the given settings, as a test states them.
   *
   * @param settingService the settings
   */
  public ConnectorEngineChoiceStorage(SettingService settingService) {
    this.settingService = settingService;
  }

  /**
   * The engine chosen for a connector.
   *
   * @param kind {@value #RULES_ENGINE} or {@value #ACL_ENGINE}
   * @param connectorId the connector
   * @return the engine's name, lower-case, or null when none was chosen
   */
  public String getChoice(String kind, long connectorId) {
    SettingValue<?> value = settingService.get(Context.GLOBAL, SCOPE, key(kind, connectorId));
    String name = value == null || value.getValue() == null ? null : value.getValue().toString();
    return StringUtils.isBlank(name) ? null : name.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Keeps the engine chosen for a connector.
   *
   * @param kind {@value #RULES_ENGINE} or {@value #ACL_ENGINE}
   * @param connectorId the connector
   * @param engineName the engine's name
   */
  public void setChoice(String kind, long connectorId, String engineName) {
    settingService.set(Context.GLOBAL, SCOPE, key(kind, connectorId), SettingValue.create(engineName.trim().toLowerCase(Locale.ROOT)));
  }

  /**
   * Forgets the engines chosen for a connector, as when it is deleted.
   *
   * @param connectorId the connector
   */
  public void removeChoices(long connectorId) {
    settingService.remove(Context.GLOBAL, SCOPE, key(RULES_ENGINE, connectorId));
    settingService.remove(Context.GLOBAL, SCOPE, key(ACL_ENGINE, connectorId));
  }

  /**
   * The setting key of one choice.
   *
   * @param kind the kind of engine
   * @param connectorId the connector
   * @return the key
   */
  private static String key(String kind, long connectorId) {
    return kind + "." + connectorId;
  }
}
