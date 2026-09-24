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
package org.exoplatform.emailConnector.rest.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The grantee's toggles on one delegation. A null leaves a toggle as it is.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DelegationPreferencesRequest {

  private Boolean badgeIncluded;

  private Boolean notifyNewMail;

  /** Whether the unified search returns this shared mailbox (EXO-90554). */
  private Boolean searchIncluded;

  /**
   * The badge and notification toggles; the search toggle left as it is.
   *
   * @param badgeIncluded the badge toggle, or null
   * @param notifyNewMail the notification toggle, or null
   */
  public DelegationPreferencesRequest(Boolean badgeIncluded, Boolean notifyNewMail) {
    this(badgeIncluded, notifyNewMail, null);
  }
}
