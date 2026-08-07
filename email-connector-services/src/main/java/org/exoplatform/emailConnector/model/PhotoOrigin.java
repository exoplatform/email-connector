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
 * Who a contact's picture belongs to.
 * <p>
 * The distinction exists because pictures arrived before the address-book sync
 * did: without it, a picture somebody chose by hand and one pulled from a vCard
 * are the same bytes in the same column, and the sync can only either trample the
 * user's choice or never refresh its own. A null reads as {@link #USER}, which is
 * what every picture stored before this existed is.
 */
public enum PhotoOrigin {

  /** Set by hand in the contact form. The sync never overwrites it. */
  USER,

  /** Written by the address-book sync, and therefore the sync's to replace. */
  VCARD
}
