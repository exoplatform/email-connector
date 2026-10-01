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
 * What became of an answered invitation in the user's calendar (EXO-90848), as the
 * reader tells it after the answer; absent when no add-on holds a calendar for the
 * user, which the reader says nothing about.
 */
public enum CalendarLanding {

  /** The user's calendar holds the event with the answer given. */
  LANDED,

  /**
   * An add-on holds the user's calendar and refused the invitation as it is -- about
   * one occurrence only, or not to be trusted; the answer left anyway.
   */
  REFUSED,

  /** An add-on holds the user's calendar and could not update it; the answer left anyway. */
  FAILED

}
