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

import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.model.ConnectorEngines;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.service.acl.MailboxAclEngineRegistry;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngineRegistry;
import org.exoplatform.emailConnector.storage.ConnectorEngineChoiceStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Which engine each connector's server rules and mailbox sharing go through, as the
 * connector administration screen reads and sets it. The choice decides what eXo
 * writes to the mail server on its users' behalf, so it is an administrator's, and it
 * is made among the engines installed: an engine an add-on contributes is offered only
 * while that add-on is installed. A deployment property, when set, still decides over
 * the choice, and the screen is told which one.
 */
@Service
public class EmailConnectorEngineService {

  /** The answer to a connector id that names no connector. */
  public static final String       CONNECTOR_NOT_FOUND = "emailConnector.engines.connectorNotFound";

  /** The refusal of an engine that is not installed. */
  public static final String       UNKNOWN_ENGINE      = "emailConnector.engines.unknown";

  private static final Log         LOG                 = ExoLogger.getLogger(EmailConnectorEngineService.class);

  @Autowired
  private EmailConnectorService    emailConnectorService;

  @Autowired
  private ServerRuleEngineRegistry serverRuleEngineRegistry;

  @Autowired
  private MailboxAclEngineRegistry mailboxAclEngineRegistry;

  @Autowired
  private ConnectorEngineChoiceStorage connectorEngineChoiceStorage;

  /** Absent where the credentials contract is not deployed; then nothing is missing. */
  @Autowired(required = false)
  private EmailCredentialsResolver emailCredentialsResolver;

  /**
   * The engines of a connector as the administration screen shows them: the ones
   * configured, the ones installed, the property overriding either when one is set,
   * and whether the connector's credentials provider is missing.
   *
   * @param connectorId the connector
   * @param username the user asking, who must administer email connectors
   * @return the engines
   * @throws IllegalAccessException when the user may not administer email connectors
   * @throws ObjectNotFoundException when no such connector exists
   */
  public ConnectorEngines getEngines(Long connectorId, String username) throws IllegalAccessException, ObjectNotFoundException {
    return enginesOf(administeredConnector(connectorId, username));
  }

  /**
   * Keeps the engines an administrator chose for a connector. Each must be installed,
   * or be the one already kept. A null one is left as kept, which is how the screen
   * saves the one it changed only. A deployment property that decides the engine still
   * wins; the choice is kept for when it is removed.
   * <p>
   * A switch applies to what comes next: what the engine switched from already wrote
   * on the mail server — mailbox access granted to colleagues, a forward or an
   * automatic reply — stays there, and is no longer read or removed from eXo through
   * the engine switched to: an automatic reply the server keeps sending is then shown
   * as off, to its user and to their delegates.
   *
   * @param connectorId the connector
   * @param engines the engines chosen; a null one keeps the one kept
   * @param username the user saving, who must administer email connectors
   * @return the engines after the save
   * @throws IllegalAccessException when the user may not administer email connectors
   * @throws ObjectNotFoundException when no such connector exists
   * @throws IllegalArgumentException {@value #UNKNOWN_ENGINE} when an engine is not
   *           installed
   */
  public ConnectorEngines saveEngines(Long connectorId,
                                      ConnectorEngines engines,
                                      String username) throws IllegalAccessException, ObjectNotFoundException {
    EmailConnector connector = administeredConnector(connectorId, username);
    if (engines == null) {
      throw new IllegalArgumentException(UNKNOWN_ENGINE);
    }
    String rules = checkedChoice(ConnectorEngineChoiceStorage.RULES_ENGINE,
                                 connector,
                                 engines.getRulesEngine(),
                                 serverRuleEngineRegistry.engineNames());
    String acl = checkedChoice(ConnectorEngineChoiceStorage.ACL_ENGINE,
                               connector,
                               engines.getAclEngine(),
                               mailboxAclEngineRegistry.engineNames());
    if (rules != null) {
      connectorEngineChoiceStorage.setChoice(ConnectorEngineChoiceStorage.RULES_ENGINE, connector.getId(), rules);
    }
    if (acl != null) {
      connectorEngineChoiceStorage.setChoice(ConnectorEngineChoiceStorage.ACL_ENGINE, connector.getId(), acl);
    }
    LOG.info("Engines of connector {} set by {}: server rules {}, mailbox sharing {}", connector.getId(), username, rules, acl);
    return enginesOf(connector);
  }

  /**
   * A connector an administrator acts on. A blank user is refused here, whatever the
   * administration check answers for one.
   *
   * @param connectorId the connector
   * @param username the user
   * @return the connector
   * @throws IllegalAccessException when the user may not administer email connectors
   * @throws ObjectNotFoundException when no such connector exists
   */
  private EmailConnector administeredConnector(Long connectorId, String username) throws IllegalAccessException,
                                                                                   ObjectNotFoundException {
    if (StringUtils.isBlank(username) || !emailConnectorService.canEdit(username)) {
      throw new IllegalAccessException("User " + username + " may not set the engines of email connector " + connectorId);
    }
    EmailConnector connector = connectorId == null ? null : emailConnectorService.getEmailConnector(connectorId);
    if (connector == null) {
      throw new ObjectNotFoundException(CONNECTOR_NOT_FOUND);
    }
    return connector;
  }

  /**
   * One engine chosen, checked: installed, or the one already kept.
   *
   * @param kind the kind of engine
   * @param connector the connector
   * @param chosen the name chosen, may be null to keep the one kept
   * @param installed the engines of that kind installed
   * @return the name to keep, lower-case, or null when nothing is to be written
   * @throws IllegalArgumentException {@value #UNKNOWN_ENGINE} when the engine is
   *           neither installed nor the one kept
   */
  private String checkedChoice(String kind, EmailConnector connector, String chosen, List<String> installed) {
    if (StringUtils.isBlank(chosen)) {
      return null;
    }
    String name = chosen.trim().toLowerCase(Locale.ROOT);
    if (installed.contains(name)) {
      return name;
    }
    if (name.equals(connectorEngineChoiceStorage.getChoice(kind, connector.getId()))) {
      return null;
    }
    throw new IllegalArgumentException(UNKNOWN_ENGINE);
  }

  /**
   * The engines of a connector as the screen shows them.
   *
   * @param connector the connector
   * @return the engines
   */
  private ConnectorEngines enginesOf(EmailConnector connector) {
    ConnectorEngines engines = new ConnectorEngines();
    engines.setRulesEngine(serverRuleEngineRegistry.engineName(connector));
    engines.setAclEngine(mailboxAclEngineRegistry.engineName(connector));
    engines.setRulesEngineChoice(serverRuleEngineRegistry.chosenEngineName(connector));
    engines.setAclEngineChoice(mailboxAclEngineRegistry.chosenEngineName(connector));
    engines.setRulesEngines(serverRuleEngineRegistry.engineNames());
    engines.setAclEngines(mailboxAclEngineRegistry.engineNames());
    engines.setRulesEngineProperty(serverRuleEngineRegistry.overridingProperty(connector));
    engines.setAclEngineProperty(mailboxAclEngineRegistry.overridingProperty(connector));
    String provider = connector.getAuthProviderName();
    engines.setAuthProviderName(provider);
    engines.setAuthProviderMissing(StringUtils.isNotBlank(provider) && emailCredentialsResolver != null
        && !emailCredentialsResolver.isProviderRegistered(provider));
    return engines;
  }
}
