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
 * The picture a contact of a user's own store carries, as the mail's avatars point
 * at it (EXO-90908): the contact, and the version its photo URL is cached under.
 *
 * @param contactId the contact's id
 * @param version the row's update time in ms, else the photo's file id: replacing a
 *          photo keeps its file id, so the version follows the row
 */
public record ContactPhoto(long contactId, long version) {
}
