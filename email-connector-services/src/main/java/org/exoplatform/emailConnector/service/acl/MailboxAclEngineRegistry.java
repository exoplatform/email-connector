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
package org.exoplatform.emailConnector.service.acl;

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
 * Which {@link MailboxAclEngine} a connector preset uses. Every engine bean is collected
 * (the service-plugin mechanism, by type), those another add-on contributes included,
 * and the preset's engine is chosen in the connector administration screen among the
 * ones installed ({@link ConnectorEngineChoiceStorage}). A boot-time property still wins
 * when set, as a deployment-wide override: {@code email.connector.aclEngine.<connectorId>}
 * for one preset, {@code email.connector.aclEngine} for all. With neither, the preset
 * uses {@code imap} -- and since the IMAP engine probes by attempting MYRIGHTS before
 * doing anything, a default of {@code imap} on a server without ACLs degrades to
 * "unsupported", never to a failed SETACL. IMAP writes never reach a BlueMind mailstore
 * (see {@code package-info}), so a BlueMind preset needs the BlueMind add-on's engine
 * once it exists.
 */
@Service
public class MailboxAclEngineRegistry {

  private static final Log        LOG                    = ExoLogger.getLogger(MailboxAclEngineRegistry.class);

  /** The property naming the engine of every preset, unless overridden per preset. */
  public static final String      ENGINE_PROPERTY        = "email.connector.aclEngine";

  /** The prefix of the per-preset property: {@code email.connector.aclEngine.<id>}. */
  public static final String      ENGINE_PROPERTY_PREFIX = ENGINE_PROPERTY + ".";

  @Autowired
  private List<MailboxAclEngine>  engines;

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
   * @return the engine, the no-op one when the configured name matches no bean
   */
  public MailboxAclEngine engineFor(EmailConnector connector) {
    String name = engineName(connector);
    for (MailboxAclEngine engine : engines) {
      if (name.equalsIgnoreCase(engine.getName())) {
        return engine;
      }
    }
    Long connectorId = connector == null ? null : connector.getId();
    if (unknownReported.add(connectorId + ":" + name)) {
      LOG.warn("No mailbox ACL engine named '{}' for connector {}; sharing is disabled on it",
               name,
               connectorId);
    }
    for (MailboxAclEngine engine : engines) {
      if (NoopAclEngine.NAME.equalsIgnoreCase(engine.getName())) {
        return engine;
      }
    }
    return new NoopAclEngine();
  }

  /**
   * The engine a connector is configured with, installed or not: its own property,
   * else the global one, else the connector administration screen's choice, else
   * {@value ImapAclEngine#NAME}.
   *
   * @param connector the connector preset
   * @return the name, lower-case, never blank
   */
  public String engineName(EmailConnector connector) {
    return EngineChoices.configuredName(ENGINE_PROPERTY, ConnectorEngineChoiceStorage.ACL_ENGINE, connector, choices, ImapAclEngine.NAME);
  }

  /**
   * The engines installed, by name: the ones a connector may be set to.
   *
   * @return the names, lower-case, each once, in the order the engines are collected
   */
  public List<String> engineNames() {
    List<String> names = new ArrayList<>();
    for (MailboxAclEngine engine : engines) {
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


  /**
   * @param connector the connector preset, possibly null
   * @return its id, or null
   */
  private Long connectorId(EmailConnector connector) {
    return connector == null ? null : connector.getId();
  }
}
