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

import java.util.List;

/**
 * What the administration drawer sends to point the whole instance at one mail
 * connector: the connector, and the groups the choice must not reach.
 * <p>
 * One body for the two facts, on purpose: they are applied by one click and
 * stored by one service call, and two requests would leave a moment where the
 * designation stands without its exclusions.
 *
 * @param connectorId technical identifier of the connector
 * @param excludedGroups the eXo group ids whose members keep choosing their own
 *          account, null or empty for none
 */
public record EmailManagedModeRequest(Long connectorId, List<String> excludedGroups) {
}
