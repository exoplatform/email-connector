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
package org.exoplatform.emailConnector.model;

import java.util.List;

/**
 * Whether the caller may set a forward, or a rule that forwards, from eXo, and within
 * which bounds: what the forwarding drawer and the filter form show. The answer only
 * shapes the interface; every write checks the same things again on the server.
 *
 * @param enabled true when the deployment enabled forwarding for the caller's connector
 *          and its server can keep a copy of a forwarded mail
 * @param reasonCode why not, a message code; null when enabled
 * @param allowedDomains the domains a destination may be in
 * @param confirmedDestinations the destinations the caller already confirmed, which need
 *          no new code
 */
public record ForwardingAuthoring(boolean enabled,
                                  String reasonCode,
                                  List<String> allowedDomains,
                                  List<String> confirmedDestinations) {

  /**
   * Keeps the lists unmodifiable, and never null.
   *
   * @param enabled whether authoring is possible
   * @param reasonCode why not
   * @param allowedDomains the allowed domains
   * @param confirmedDestinations the confirmed destinations
   */
  public ForwardingAuthoring {
    allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
    confirmedDestinations = confirmedDestinations == null ? List.of() : List.copyOf(confirmedDestinations);
  }
}
