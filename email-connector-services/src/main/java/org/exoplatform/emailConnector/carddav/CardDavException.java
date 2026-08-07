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
 * Anything the address-book server did that stops a sync: it was unreachable, it
 * refused the credentials, it answered something that is not CardDAV, or it
 * answered nothing useful. Unchecked on purpose — the sync service catches it in
 * one place and turns it into a failure status, and no caller can do anything
 * more specific than that.
 */
public class CardDavException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message what was being attempted, in terms of the protocol
   */
  public CardDavException(String message) {
    super(message);
  }

  /**
   * @param message what was being attempted, in terms of the protocol
   * @param cause the transport or parsing failure underneath
   */
  public CardDavException(String message, Throwable cause) {
    super(message, cause);
  }
}
