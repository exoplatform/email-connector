/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.emailConnector.model;

/**
 * The platform user a mail address belongs to, as last resolved by the
 * {@code EmailSenderProfileService} (EXO-90891).
 *
 * @param username the eXo login, or null for nobody
 * @param mailbox whether the address is their connected mailbox's, not their account's
 * @param readAt when it was resolved, in ms
 */
public record SenderAddressOwner(String username, boolean mailbox, long readAt) {
}
