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

/**
 * Whose mailbox a searchable folder of a shared mailbox belongs to (EXO-90554): what a
 * unified-search hit read from that folder carries, so the card names the owner and the
 * click opens the mail drawer on that shared mailbox.
 *
 * @param delegationId the share, as the mail drawer's {@code mailbox=} opening names it
 * @param ownerFullName the owner's display name, resolved once per share
 */
public record SharedMailboxSearchScope(long delegationId, String ownerFullName) {
}
