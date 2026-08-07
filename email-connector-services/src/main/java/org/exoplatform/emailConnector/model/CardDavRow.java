/**
 * Copyright (C) 2025 eXo Platform SAS
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
 * What the sync needs to know about a stored contact before deciding what to do
 * with it — and no more.
 * <p>
 * Deliberately not the full contact DTO: the sync's decisions turn on where a row
 * came from, whether the user hid it, whether it has correspondence history, and
 * which server entry it belongs to. The columns that answer those questions are
 * entity-only, because the REST DTO has no business carrying sync bookkeeping.
 *
 * @param id the row id
 * @param primaryEmail the address it is keyed on
 * @param source where the row came from
 * @param suppressed whether the user hid it
 * @param seenCount how much correspondence it has, which decides whether a row
 *          the server dropped is demoted or deleted
 * @param href the server entry it belongs to, or null
 * @param etag the version last synced, so an unchanged entry is not re-read
 * @param photoFileId the stored picture, or null
 * @param photoOrigin who that picture belongs to
 */
public record CardDavRow(long id,
                         String primaryEmail,
                         String source,
                         boolean suppressed,
                         long seenCount,
                         String href,
                         String etag,
                         Long photoFileId,
                         PhotoOrigin photoOrigin) {
}
