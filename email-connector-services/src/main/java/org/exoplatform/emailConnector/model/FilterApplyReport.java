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
 * What one pass of a rule over the mail already in the owner's inbox did.
 *
 * @param scanned how many cached mails were looked at
 * @param matched how many the rule matched and had not handled before
 * @param alreadyHandled how many it matched but had already handled
 * @param queued how many were queued for the assistant
 * @param notApplicable how many could not be decided on a cached mail (a header or size
 *          condition)
 */
public record FilterApplyReport(int scanned, int matched, int alreadyHandled, int queued, int notApplicable) {
}
