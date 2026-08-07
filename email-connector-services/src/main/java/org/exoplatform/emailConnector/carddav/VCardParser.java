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

package org.exoplatform.emailConnector.carddav;

/**
 * Reads vCard text into the handful of fields a contact row keeps.
 * <p>
 * An interface because vCard is the messy part of CardDAV — three versions in
 * the wild, several encodings, folding rules — and the sync should never care
 * which library is doing that work, nor be rewritten if the library changes.
 */
public interface VCardParser {

  /**
   * Reads one vCard.
   *
   * @param vcardText the raw vCard
   * @return the parsed fields, or null when the text holds no usable vCard —
   *         which the sync treats as an entry to skip, not as a failure
   */
  ParsedVCard parse(String vcardText);
}
