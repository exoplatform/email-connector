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
 * A publish the server could not take right now, put aside to retry after the
 * next successful sync — the one {@link CardDavException} that is a promise
 * rather than a failure. Its own type so the REST layer can answer it 202
 * ("accepted, not done") while every other CardDAV failure keeps its 502: the
 * distinction is precisely whether the user's click is safe to walk away from.
 */
public class CardDavPublishQueuedException extends CardDavException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message what could not be reached, in the underlying failure's words
   * @param cause the transport failure that made the publish wait
   */
  public CardDavPublishQueuedException(String message, Throwable cause) {
    super(message, cause);
  }
}
