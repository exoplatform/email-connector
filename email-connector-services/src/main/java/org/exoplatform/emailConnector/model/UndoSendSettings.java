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

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The user's "Undo send" preference (EXO-90837), stored as a JSON document of its own in
 * the setting service (no schema): how many seconds a sent mail waits, with an Undo,
 * before it goes. Zero is "off": the mail goes at once.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UndoSendSettings {

  /** The wait in seconds, one of {@link #allowedDelays}; zero sends at once. */
  private int           delaySeconds;

  /**
   * The waits the server accepts, in the order the settings screen offers them.
   * Answered on reads, so the screen never keeps a copy of its own; ignored on writes,
   * never stored.
   */
  private List<Integer> allowedDelays;
}
