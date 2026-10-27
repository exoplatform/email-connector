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
 * What the add-on holding the user's calendar said about an invitation (EXO-90873):
 * whether it answered in time, and the copy it holds when it does. "Nothing held" and
 * "nothing said" read alike on the card; they do not where a landing depends on whether
 * the calendar holds the event already.
 *
 * @param answered whether the add-on answered: false on a late answer, a full
 *          executor, a failure, or no add-on to ask
 * @param held the copy held, null when the add-on answered that it holds none, or did
 *          not answer
 */
public record HeldLookup(boolean answered, HeldInvitation held) {

  /** Nothing could be said in time. */
  private static final HeldLookup UNANSWERED = new HeldLookup(false, null);

  /**
   * @return the lookup the add-on did not answer
   */
  public static HeldLookup unanswered() {
    return UNANSWERED;
  }

  /**
   * @param held the copy the add-on holds, null when it holds none
   * @return the lookup the add-on answered
   */
  public static HeldLookup answered(HeldInvitation held) {
    return new HeldLookup(true, held);
  }
}
