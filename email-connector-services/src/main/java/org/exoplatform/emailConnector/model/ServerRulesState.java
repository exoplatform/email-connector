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
 * Where the server rules eXo manages stand on the mail server, compared with what eXo
 * last wrote.
 */
public enum ServerRulesState {
  /** eXo's rules are on the server as eXo wrote them, and the server runs them. */
  OWN,
  /**
   * eXo's rules are on the server as eXo wrote them, but the server no longer runs them:
   * another client activated its own script ("Re-activate").
   */
  INACTIVE,
  /** eXo's script was changed outside eXo since eXo last wrote it ("Re-publish"). */
  MODIFIED,
  /**
   * eXo's script is on the server but its header cannot be read as eXo's: nothing can be
   * written back from it, so eXo does not write over it. The user repairs or deletes it
   * in their mail client.
   */
  UNREADABLE,
  /** No script of eXo's is on the server. */
  NONE
}
