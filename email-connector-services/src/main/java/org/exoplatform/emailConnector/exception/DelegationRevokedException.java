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
package org.exoplatform.emailConnector.exception;

/**
 * The share a grantee is acting on no longer exists on the server: the owner or an
 * administrator removed the access, or the mailbox is gone. A business state and not
 * a refusal -- the REST layer maps {@code IllegalAccessException} to 403, which would
 * be the wrong word for it -- so the REST layer answers {@code 410 Gone}
 * with the code as message, and the drawer switches back to the user's own mailbox.
 */
public class DelegationRevokedException extends RuntimeException {

  private static final long  serialVersionUID = 1L;

  /** The access was removed on the server. */
  public static final String REVOKED          = "emailConnector.delegation.revoked";

  /** The shared mailbox is no longer listed to the grantee. */
  public static final String GONE             = "emailConnector.delegation.gone";

  /**
   * @param code the message code the interface translates
   */
  public DelegationRevokedException(String code) {
    super(code);
  }
}
