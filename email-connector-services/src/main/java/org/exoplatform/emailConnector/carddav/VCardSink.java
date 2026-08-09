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
 * Where a multi-card read delivers its cards, one at a time.
 * <p>
 * A callback rather than a returned list because the input is an uploaded file
 * nobody vetted: a list would hold every card of a 20&nbsp;MB export in memory
 * at once, while a sink lets the caller stop at its own cap and keep only its
 * counters. Both answers say whether to keep reading, which is how a cap
 * reaches the reader without the reader knowing any cap exists.
 */
public interface VCardSink {

  /**
   * One card the reader could turn into fields.
   *
   * @param card the parsed card, never null
   * @return true to keep reading, false to stop
   */
  boolean accept(ParsedVCard card);

  /**
   * One card the reader could not turn into fields — counted by the caller,
   * never a failed read: a file of a thousand cards must not be held hostage by
   * one of them.
   *
   * @return true to keep reading, false to stop
   */
  default boolean acceptUnreadable() {
    return true;
  }
}
