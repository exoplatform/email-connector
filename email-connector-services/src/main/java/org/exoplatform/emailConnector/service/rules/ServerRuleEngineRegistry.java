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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.storage.ConnectorEngineChoiceStorage;
import org.exoplatform.emailConnector.utils.EngineChoices;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Which {@link ServerRuleEngine} a connector preset uses. Every engine bean is collected
 * by type, those another add-on contributes included, and the preset's engine is chosen
 * in the connector administration screen among the ones installed
 * ({@link ConnectorEngineChoiceStorage}). A boot-time property still wins when set, as
 * a deployment-wide override: {@code email.connector.rulesEngine.<connectorId>} for one
 * preset, {@code email.connector.rulesEngine} for all. With neither, the preset uses
 * {@value NoopRuleEngine#NAME} -- it manages nothing on its server until an
 * administrator says which engine its server speaks. Not the preset's
 * {@code providerConfig}: that map is inbound-only and validated against the credentials
 * provider's own descriptor.
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

  /** The engines the connector administration screen chose; absent in a bare test. */
  @Autowired(required = false)
  private ConnectorEngineChoiceStorage choices;

  /**
   * The engine names already reported as not installed, per connector: each is said
   * once at WARN rather than on every request that meets it.
   */
  private final Set<String>           unknownReported = ConcurrentHashMap.newKeySet();

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
    Long connectorId = connector == null ? null : connector.getId();
    if (unknownReported.add(connectorId + ":" + name)) {
      LOG.warn("No server rules engine named '{}' for connector {}; its automatic reply is not managed from eXo",
               name,
               connectorId);
    }
    for (ServerRuleEngine engine : engines) {
      if (NoopRuleEngine.NAME.equalsIgnoreCase(engine.getName())) {
        return engine;
      }
    }
    return new NoopRuleEngine();
  }

  /**
   * The engine a connector is configured with, installed or not: its own property,
   * else the global one, else the connector administration screen's choice, else
   * {@value NoopRuleEngine#NAME}.
   *
   * @param connector the connector preset
   * @return the name, lower-case, never blank
   */
  public String engineName(EmailConnector connector) {
    return EngineChoices.configuredName(ENGINE_PROPERTY, ConnectorEngineChoiceStorage.RULES_ENGINE, connector, choices, NoopRuleEngine.NAME);
  }

  /**
   * The engines installed, by name: the ones a connector may be set to.
   *
   * @return the names, lower-case, each once, in the order the engines are collected
   */
  public List<String> engineNames() {
    List<String> names = new ArrayList<>();
    for (ServerRuleEngine engine : engines) {
      String name = engine.getName() == null ? null : engine.getName().trim().toLowerCase(Locale.ROOT);
      if (StringUtils.isNotBlank(name) && !names.contains(name)) {
        names.add(name);
      }
    }
    return names;
  }

  /**
   * The deployment property that decides a connector's engine over the connector
   * administration screen's choice.
   *
   * @param connector the connector preset
   * @return the property's name, or null when none is set
   */
  public String overridingProperty(EmailConnector connector) {
    return EngineChoices.overridingProperty(ENGINE_PROPERTY, connector);
  }

}
