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
package org.exoplatform.emailConnector.service.rules.sieve;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.emailConnector.model.EmailConnector;

/**
 * Where a connector preset's ManageSieve server listens, as the administrator set it.
 * <p>
 * Boot-time properties, read like the other per-preset selectors of this add-on: the
 * preset's own key wins over the global one, which wins over the default —
 * {@code email.connector.sieve.host[.<connectorId>]}, defaulting to the preset's IMAP
 * host (the same server in every deployment met so far, and a host the administrator
 * already trusts), and {@code email.connector.sieve.port[.<connectorId>]}, defaulting
 * to {@value ManageSieveClient#DEFAULT_PORT}. No request parameter and no user setting
 * reaches either: the host is at the trust level of the IMAP URL.
 *
 * @param host the host, never blank
 * @param port the port, in 1..65535
 */
public record ManageSieveEndpoint(String host, int port) {

  /** The host property, suffixed with {@code .<connectorId>} for one preset. */
  public static final String HOST_PROPERTY = "email.connector.sieve.host";

  /** The port property, suffixed with {@code .<connectorId>} for one preset. */
  public static final String PORT_PROPERTY = "email.connector.sieve.port";

  /**
   * Validates the endpoint.
   *
   * @param host the host
   * @param port the port
   */
  public ManageSieveEndpoint {
    if (StringUtils.isBlank(host)) {
      throw new IllegalStateException("No ManageSieve host: set " + HOST_PROPERTY + " or the connector's IMAP host");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalStateException("Invalid ManageSieve port " + port);
    }
    host = host.trim();
  }

  /**
   * The endpoint of a connector preset.
   *
   * @param connector the preset
   * @return its ManageSieve endpoint
   * @throws IllegalStateException when no host can be resolved or the port property is
   *           not a valid port
   */
  public static ManageSieveEndpoint forConnector(EmailConnector connector) {
    String host = property(HOST_PROPERTY, connector);
    if (StringUtils.isBlank(host) && connector != null) {
      host = connector.getImapUrl();
    }
    String portValue = property(PORT_PROPERTY, connector);
    int port;
    if (StringUtils.isBlank(portValue)) {
      port = ManageSieveClient.DEFAULT_PORT;
    } else {
      try {
        port = Integer.parseInt(portValue.trim());
      } catch (NumberFormatException e) {
        throw new IllegalStateException("Invalid ManageSieve port property value '" + portValue + "'", e);
      }
    }
    return new ManageSieveEndpoint(host, port);
  }

  /**
   * A property's value for a preset: its own key, else the global one.
   *
   * @param name the global property name
   * @param connector the preset, possibly null
   * @return the value, possibly null
   */
  private static String property(String name, EmailConnector connector) {
    String value = null;
    if (connector != null && connector.getId() != null) {
      value = System.getProperty(name + "." + connector.getId());
    }
    return StringUtils.isBlank(value) ? System.getProperty(name) : value;
  }
}
