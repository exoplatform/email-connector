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
package org.exoplatform.emailConnector.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The user's read-receipt preferences, stored as a JSON document of its own in the
 * setting service (no schema): whether the composer requests a receipt by default,
 * and what to do when a received message requests one.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReadReceiptSettings {

  /**
   * Whether the composer's "Request a read receipt" starts checked. A default for the
   * composer only: the server never adds the request on its own, a send carries it
   * when its payload says so.
   */
  private boolean           requestByDefault;

  /** What to do with incoming requests; null reads as {@link ReadReceiptPolicy#ASK}. */
  private ReadReceiptPolicy responsePolicy;

  /**
   * Whether the administrator allows {@link ReadReceiptPolicy#ALWAYS}. Answered on
   * reads so the settings screen can hide the choice; never stored, ignored on writes.
   */
  private boolean           alwaysAllowed;
}
