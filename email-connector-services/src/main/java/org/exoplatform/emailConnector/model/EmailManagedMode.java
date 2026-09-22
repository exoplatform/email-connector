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

/**
 * Whether this deployment chooses the mail connector on its users' behalf, and
 * which one.
 * <p>
 * Two answers in one payload, and they are deliberately not the same question.
 * {@code connectorId}, {@code connectorName} and {@code excludedGroups} say what
 * the <b>instance</b> decided — the administration screen renders them, and they
 * are the same for everybody. {@code managedForMe} says whether the decision
 * applies to <b>the caller</b>: the designation exists and the caller is in none
 * of the excluded groups. It governs the automatic attachment at login and
 * nothing else — never whether the caller may connect a connector of their own.
 *
 * @param connectorId the connector the instance chose, null when managed mode is
 *          off
 * @param connectorName that connector's display name, null when managed mode is
 *          off — the screens name the connector rather than print its id
 * @param excludedGroups the eXo group ids whose members the choice does not
 *          reach, empty when managed mode is off or excludes nobody
 * @param managedForMe whether the caller is governed by that choice
 */
public record EmailManagedMode(Long connectorId, String connectorName, List<String> excludedGroups, boolean managedForMe) {
}
