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
 * What an export would hold, checked before the download starts (EXO-90845): how many
 * messages, and the most one file may hold. The interface reads the cap from here rather
 * than keeping a copy of it, and says "too many" before the browser starts a download
 * that would only fail.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ExportCheck {

  /** How many messages the export would hold. */
  private int count;

  /** The most messages one file of that kind may hold. */
  private int max;
}
