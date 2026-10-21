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

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Whether users of one connector may forward their mail from eXo, and to which domains,
 * as the connector administration screen sets it and as it applies.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectorForwarding {

  /** Whether users may set a forward, or a filter that forwards, from eXo. */
  private boolean      authoringEnabled;

  /**
   * The domains a forward may go to, exact, lower-case; empty for the domain of each
   * user's own mailbox.
   */
  private List<String> allowedDomains = new ArrayList<>();

  /**
   * Answered only: whether the screen was ever saved for this connector. Until it is, the
   * deployment's properties apply.
   */
  private boolean      saved;

  /**
   * Answered only: whether the deployment switched forwarding off everywhere
   * ({@code email.connector.forwarding.authoring.enabled=false}), which wins over the
   * screen.
   */
  private boolean      killSwitch;
}
