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
package org.exoplatform.emailConnector.utils;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.storage.ConnectorEngineChoiceStorage;

/**
 * How the engine registries read which engine a connector uses: the deployment
 * property of the connector, else the deployment-wide one, else the connector
 * administration screen's choice, else the default. A property set wins over the
 * screen, so a deployment configured by properties keeps behaving as configured.
 */
public final class EngineChoices {

  /**
   * Not instantiable.
   */
  private EngineChoices() {
  }

  /**
   * The deployment property that decides a connector's engine over the screen's
   * choice: the connector's own when set, else the deployment-wide one when set.
   *
   * @param prefix the connector's own property without its id, such as
   *          {@code email.connector.rulesEngine.}; the deployment-wide one is it
   *          without its trailing dot
   * @param connector the connector, may be null
   * @return the property's name, or null when neither is set
   */
  public static String overridingProperty(String prefix, EmailConnector connector) {
    String property = StringUtils.removeEnd(prefix, ".");
    if (connector != null && connector.getId() != null) {
      String own = prefix + connector.getId();
      if (StringUtils.isNotBlank(System.getProperty(own))) {
        return own;
      }
    }
    return StringUtils.isNotBlank(System.getProperty(property)) ? property : null;
  }

  /**
   * The engine a connector is configured with.
   *
   * @param prefix the connector's own property without its id, as
   *          {@link #overridingProperty} takes it
   * @param kind the kind of engine, as the storage keys it
   * @param connector the connector, may be null
   * @param choices the screen's choices, may be null
   * @param defaultName the engine used when nothing is configured
   * @return the name, lower-case, never blank
   */
  public static String configuredName(String prefix,
                                      String kind,
                                      EmailConnector connector,
                                      ConnectorEngineChoiceStorage choices,
                                      String defaultName) {
    String overriding = overridingProperty(prefix, connector);
    String name = overriding == null ? null : System.getProperty(overriding);
    return StringUtils.isBlank(name) ? chosenName(kind, connector, choices, defaultName) : name.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * The engine the screen chose for a connector, whatever a property decides.
   *
   * @param kind the kind of engine, as the storage keys it
   * @param connector the connector, may be null
   * @param choices the screen's choices, may be null
   * @param defaultName the engine used when nothing was chosen
   * @return the name, lower-case, never blank
   */
  public static String chosenName(String kind, EmailConnector connector, ConnectorEngineChoiceStorage choices, String defaultName) {
    String name = choices == null || connector == null || connector.getId() == null ? null : choices.getChoice(kind, connector.getId());
    return StringUtils.isBlank(name) ? defaultName : name.trim().toLowerCase(Locale.ROOT);
  }
}
