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
package org.exoplatform.emailConnector.service;

/**
 * A second proof of identity before a forward is set from eXo -- a re-entry of the
 * mailbox password -- plugs in here. A session thief who holds eXo's session but
 * not the mailbox password would be stopped by it -- once the settings endpoint no
 * longer answers the decoded password, which today makes such a check prove nothing.
 * No implementation ships: when a bean implements this interface, the forwarding service
 * calls it before sending a code, confirming a destination, setting a forward or saving a
 * rule that forwards.
 */
public interface ForwardingStepUp {

  /**
   * Checks the caller's second proof of identity for a forwarding change.
   *
   * @param username the caller, from the request's session
   * @throws IllegalAccessException when the proof is missing or wrong
   */
  void verify(String username) throws IllegalAccessException;
}
