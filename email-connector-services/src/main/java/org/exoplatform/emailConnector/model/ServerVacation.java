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
 * What an engine read, or left after a write, of the user's automatic reply on the mail
 * server.
 *
 * @param state what the server holds compared with what eXo wrote; an engine answers
 *          {@link VacationState#OWN} for its own script and leaves the comparison with
 *          the stored hash to the service
 * @param vacation the reply eXo can show, possibly null
 * @param foreignScriptName the other client's active script, when the state names one;
 *          possibly empty (a nameless script)
 * @param scriptHash the SHA-256 of eXo's script as the server holds it, null when the
 *          engine has no script
 */
public record ServerVacation(VacationState state, VacationSetting vacation, String foreignScriptName, String scriptHash) {

  /**
   * Nothing known.
   *
   * @return a {@link VacationState#NONE} answer without a reply
   */
  public static ServerVacation none() {
    return new ServerVacation(VacationState.NONE, null, null, null);
  }
}
