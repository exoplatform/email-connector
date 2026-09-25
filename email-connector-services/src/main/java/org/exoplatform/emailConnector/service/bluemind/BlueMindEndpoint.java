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
package org.exoplatform.emailConnector.service.bluemind;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.emailConnector.model.EmailConnector;

/**
 * Where a connector preset's BlueMind core API lives, from the administrator's
 * configuration only: the boot-time property
 * {@code email.connector.bluemind.coreUrl.<connectorId>}, else
 * {@code email.connector.bluemind.coreUrl}, else the scheme, host and port of the
 * preset's {@code webmailUrl} followed by {@code /api} -- never its {@code imapUrl}, which
 * on the deployment observed is another host. Nothing a user sends can name the host.
 * <p>
 * The root must be {@code https}; plain {@code http} is accepted for a loopback host
 * only, since the account's password travels in the login's body.
 */
public final class BlueMindEndpoint {

  /** The property naming the core API root, per preset with a {@code .<id>} suffix. */
  public static final String CORE_URL_PROPERTY = "email.connector.bluemind.coreUrl";

  /** The path BlueMind serves its REST API under. */
  static final String        API_PATH          = "/api";

  /**
   * No instance: a holder of static resolution.
   */
  private BlueMindEndpoint() {
  }

  /**
   * The core API root of a preset.
   *
   * @param connector the preset
   * @return the root, without a trailing slash, e.g. {@code https://mail.example.com/api}
   * @throws IllegalStateException when neither the property nor the preset's webmail
   *           address give a usable root
   */
  public static String apiRootOf(EmailConnector connector) {
    String configured = property(connector);
    if (StringUtils.isNotBlank(configured)) {
      return normalise(configured.trim(), true);
    }
    String webmail = connector == null ? null : connector.getWebmailUrl();
    if (StringUtils.isBlank(webmail)) {
      throw new IllegalStateException("No BlueMind core API: set " + CORE_URL_PROPERTY + " or the connector's webmail URL");
    }
    return normalise(webmail.trim(), false);
  }

  /**
   * A usable root from an address: its scheme, host and port, with its own path when the
   * administrator configured the root, else {@value #API_PATH}.
   *
   * @param address the address
   * @param keepPath whether the address's own path is the API's
   * @return the root
   * @throws IllegalStateException when the address is not usable
   */
  private static String normalise(String address, boolean keepPath) {
    URI uri;
    try {
      uri = URI.create(address);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("The BlueMind core API address is not a URL", e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost();
    if (StringUtils.isBlank(host)) {
      throw new IllegalStateException("The BlueMind core API address has no host");
    }
    if (!"https".equals(scheme) && !("http".equals(scheme) && isLoopback(host))) {
      throw new IllegalStateException("The BlueMind core API address must be https");
    }
    String path = keepPath ? StringUtils.removeEnd(StringUtils.defaultString(uri.getRawPath()), "/") : "";
    if (path.isEmpty()) {
      path = API_PATH;
    }
    return scheme + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + path;
  }

  /**
   * Whether a host is this machine, by name or by address, without a DNS lookup.
   *
   * @param host the host
   * @return true for a loopback host
   */
  private static boolean isLoopback(String host) {
    if ("localhost".equalsIgnoreCase(host)) {
      return true;
    }
    String literal = StringUtils.removeEnd(StringUtils.removeStart(host, "["), "]");
    boolean ipv4 = literal.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    boolean ipv6 = literal.indexOf(':') >= 0 && literal.matches("[0-9a-fA-F:.]+");
    if (!ipv4 && !ipv6) {
      return false;
    }
    try {
      return InetAddress.getByName(literal).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }

  /**
   * The configured root: the preset's own property, else the global one.
   *
   * @param connector the preset
   * @return the value, possibly null
   */
  private static String property(EmailConnector connector) {
    String value = null;
    if (connector != null && connector.getId() != null) {
      value = System.getProperty(CORE_URL_PROPERTY + "." + connector.getId());
    }
    return StringUtils.isBlank(value) ? System.getProperty(CORE_URL_PROPERTY) : value;
  }
}
