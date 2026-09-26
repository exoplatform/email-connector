/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.exception;

/**
 * The Sieve generator refused to write a {@code redirect}: its destination did not pass
 * the forwarding checks for this caller (not confirmed, or no longer in the connector's
 * allowed domains), or the server would not keep a copy. The generator's last line of
 * defence, behind the service's own checks: whatever a script's header holds -- a rule
 * or a forward read back from the server, even one edited outside eXo and re-published
 * -- no {@code redirect} reaches the server unless this caller's checks allow it.
 * Nothing was written. The REST layer answers 403 with the code as message.
 */
public class ForwardingRefusedException extends IllegalArgumentException {

  private static final long  serialVersionUID  = 1L;

  /** A destination the caller's forwarding checks do not allow. */
  public static final String NOT_AUTHORIZED    = "emailConnector.forwarding.notAuthorized";

  /** The server does not advertise {@code copy}: a forward would not keep the mail. */
  public static final String COPY_UNSUPPORTED  = "emailConnector.forwarding.copyUnsupported";

  /**
   * A refusal.
   *
   * @param code one of the codes of this class
   */
  public ForwardingRefusedException(String code) {
    super(code);
  }
}
