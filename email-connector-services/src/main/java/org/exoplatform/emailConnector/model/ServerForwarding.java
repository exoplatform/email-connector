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
 * What a rules engine answers after it wrote the caller's forward: the forward as the
 * server now holds it, and the hash of eXo's script when the engine keeps one, which the
 * caller stores like every other write of that script.
 *
 * @param forwarding the forward, read back after the write
 * @param scriptHash the SHA-256 of eXo's script as written; null for an engine without
 *          a script (BlueMind)
 */
public record ServerForwarding(ForwardingSetting forwarding, String scriptHash) {
}
