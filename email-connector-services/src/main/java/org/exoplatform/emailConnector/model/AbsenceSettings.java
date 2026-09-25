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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The automatic reply section of the user's settings, as {@code GET /absence} answers
 * it: a live read of the mail server, never a copy kept by eXo.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AbsenceSettings {

  /** What this connector's engine can do; greys out the form per element. */
  private ServerRuleCapabilities capabilities;

  /** The engine name the connector is configured with: {@code sieve} or {@code none}. */
  private String                 engine;

  /** The reply eXo can show, possibly null. */
  private VacationSetting        vacation;

  /** What the server holds compared with what eXo wrote. */
  private VacationState          vacationState;

  /** The other client's active script when the state names one. */
  private String                 foreignScriptName;

  /** The minimum number of days between two replies to one sender, the deployment's. */
  private int                    vacationDays;
}
