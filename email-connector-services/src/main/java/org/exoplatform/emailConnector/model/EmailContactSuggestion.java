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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of the compose field's suggestion list: exactly what a recipient chip
 * needs to render and nothing else.
 * <p>
 * Deliberately NOT an {@link EmailContact}. A suggestion is answered on every
 * keystroke, so it carries no store bookkeeping (source, counters, suppression,
 * photo file id, dates) — none of which the field can use, all of which would
 * tell a caller more about the user's store than a type-ahead needs to. It is
 * also the shape the platform-directory half of the answer can honestly fill:
 * a colleague who is not in the store has no contact row behind them at all.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailContactSuggestion {

  /** The normalized address the chip resolves to; the list's dedupe key. */
  private String  address;

  /** The name to show, or null when all that is known is the address. */
  private String  displayName;

  /**
   * The avatar to show: the user's own contact picture when they set one,
   * otherwise the platform profile's, otherwise null (the field draws initials).
   */
  private String  avatarUrl;

  /**
   * Whether this address belongs to a platform user — what lets the field mark
   * a colleague apart from an outside correspondent.
   */
  private boolean platformUser;

  /** The platform profile link, set only when {@link #platformUser} is true. */
  private String  profileUrl;
}
