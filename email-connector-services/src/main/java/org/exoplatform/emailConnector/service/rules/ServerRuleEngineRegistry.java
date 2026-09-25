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
package org.exoplatform.emailConnector.service.rules;

import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Which {@link ServerRuleEngine} a connector preset uses. Every engine bean is collected
 * by type, and the preset's choice is a boot-time property, read the way the mailbox ACL
 * engine's is: {@code email.connector.rulesEngine.<connectorId>} for one preset,
 * {@code email.connector.rulesEngine} for all, {@value NoopRuleEngine#NAME} when neither
 * is set -- a preset manages nothing on its server until an administrator says which
 * engine its server speaks. Not the preset's {@code providerConfig}: that map is
 * inbound-only and validated against the credentials provider's own descriptor.
 */
@Service
public class ServerRuleEngineRegistry {

  private static final Log       LOG                    = ExoLogger.getLogger(ServerRuleEngineRegistry.class);

  /** The property naming the engine of every preset, unless overridden per preset. */
  public static final String     ENGINE_PROPERTY        = "email.connector.rulesEngine";

  /** The prefix of the per-preset property: {@code email.connector.rulesEngine.<id>}. */
  public static final String     ENGINE_PROPERTY_PREFIX = ENGINE_PROPERTY + ".";

  @Autowired
  private List<ServerRuleEngine> engines;

  /**
   * The engine of a preset.
   *
   * @param connector the connector preset
   * @return the engine; the no-op one when the configured name matches no bean
   */
  public ServerRuleEngine engineFor(EmailConnector connector) {
    String name = engineName(connector);
    for (ServerRuleEngine engine : engines) {
      if (name.equalsIgnoreCase(engine.getName())) {
        return engine;
      }
    }
    LOG.warn("No server rules engine named '{}' for connector {}; its automatic reply is not managed from eXo",
             name,
             connector == null ? null : connector.getId());
    for (ServerRuleEngine engine : engines) {
      if (NoopRuleEngine.NAME.equalsIgnoreCase(engine.getName())) {
        return engine;
      }
    }
    return new NoopRuleEngine();
  }

  /**
   * The configured engine name of a preset: its own property, else the global one, else
   * {@value NoopRuleEngine#NAME}.
   *
   * @param connector the connector preset
   * @return the name, lower-case, never blank
   */
  String engineName(EmailConnector connector) {
    String name = null;
    if (connector != null && connector.getId() != null) {
      name = System.getProperty(ENGINE_PROPERTY_PREFIX + connector.getId());
    }
    if (StringUtils.isBlank(name)) {
      name = System.getProperty(ENGINE_PROPERTY);
    }
    return StringUtils.isBlank(name) ? NoopRuleEngine.NAME : name.trim().toLowerCase(Locale.ROOT);
  }
}
