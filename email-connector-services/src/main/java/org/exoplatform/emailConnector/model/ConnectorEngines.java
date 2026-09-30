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

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Which engine a connector's server rules (automatic reply, forwarding) and mailbox
 * sharing go through, as the connector administration screen sets it and as it
 * applies.
 */
@Data
@NoArgsConstructor
public class ConnectorEngines {

  /**
   * The server rules engine: in a save, the one chosen, null to keep the stored one;
   * in an answer, the one configured, which may be an engine that is not installed.
   */
  private String       rulesEngine;

  /**
   * The mailbox sharing engine: in a save, the one chosen, null to keep the stored
   * one; in an answer, the one configured, which may be an engine that is not
   * installed.
   */
  private String       aclEngine;

  /** Answered only: the server rules engines installed, by name. */
  private List<String> rulesEngines         = new ArrayList<>();

  /** Answered only: the mailbox sharing engines installed, by name. */
  private List<String> aclEngines           = new ArrayList<>();

  /**
   * Answered only: the deployment property that decides the server rules engine over
   * this screen's choice, or null when none is set.
   */
  private String       rulesEngineProperty;

  /**
   * Answered only: the deployment property that decides the mailbox sharing engine
   * over this screen's choice, or null when none is set.
   */
  private String       aclEngineProperty;

  /** Answered only: the credentials provider the connector is configured with. */
  private String       authProviderName;

  /**
   * Answered only: whether that provider is not installed on this platform, as while
   * the add-on contributing it is not.
   */
  private boolean      authProviderMissing;
}
