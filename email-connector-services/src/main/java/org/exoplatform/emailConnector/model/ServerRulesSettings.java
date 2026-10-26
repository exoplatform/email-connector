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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The server group of the user's filters, as {@code GET /email-box/filters/server}
 * answers it: a live read of the mail server, never a copy kept by eXo.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServerRulesSettings {

  /**
   * What this connector's engine can do; greys out the form per element. Null after a
   * write, which does not read them again.
   */
  private ServerRuleCapabilities capabilities;

  /**
   * The engine name the connector is configured with: {@code sieve}, {@code bluemind} or
   * {@code none}.
   */
  private String                 engine;

  /** The rules eXo manages on the server, in the order the server applies them. */
  private List<ServerRule>       rules;

  /** Where they stand compared with what eXo last wrote. */
  private ServerRulesState       state;

  /**
   * The script another client manages that the server also runs, or that is in the way
   * of eXo's; null when none.
   */
  private String                 foreignScriptName;

  /**
   * Whether the user already agreed that eXo manages rules on their mail server; the
   * first save asks once.
   */
  private boolean                consented;
}
