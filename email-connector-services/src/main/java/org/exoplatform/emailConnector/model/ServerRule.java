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
 * One server-side rule, as a rules engine lists and saves it. Declared with the rules
 * verbs of the engine SPI so that the seam is not retrofitted for its second consumer;
 * the server-rules eXip completes its conditions and actions and implements the verbs.
 *
 * @param ref the engine's reference of the rule, stable across saves
 * @param name the rule's name, as the user gave it
 * @param enabled whether the server applies it
 */
public record ServerRule(String ref, String name, boolean enabled) {
}
