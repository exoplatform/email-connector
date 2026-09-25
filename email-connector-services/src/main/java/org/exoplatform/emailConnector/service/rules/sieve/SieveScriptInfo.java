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
package org.exoplatform.emailConnector.service.rules.sieve;

/**
 * One line of a {@code LISTSCRIPTS} answer (RFC 5804 §2.7): a script the account holds,
 * and whether it is the one the server runs at delivery.
 *
 * @param name the script name, as the server stores it
 * @param active whether the server marked it {@code ACTIVE}
 */
public record SieveScriptInfo(String name, boolean active) {
}
