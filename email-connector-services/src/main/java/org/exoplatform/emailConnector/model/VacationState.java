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

/**
 * What the mail server holds about the user's automatic reply, compared with what eXo
 * last wrote.
 */
public enum VacationState {
  /** eXo's reply is on the server as eXo wrote it, running or switched off. */
  OWN,
  /**
   * Another client's active script may send its own reply: eXo shows it, names the
   * script, and does not offer to edit or overwrite it.
   */
  ELSEWHERE,
  /** eXo's script was changed outside eXo since eXo last wrote it: "Re-publish". */
  MODIFIED,
  /**
   * eXo's reply is switched on in its script, but the server no longer runs that script:
   * "Re-activate", never silently.
   */
  INACTIVE,
  /** No automatic reply is known. */
  NONE
}
