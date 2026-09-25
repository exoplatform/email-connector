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
package org.exoplatform.emailConnector.model;

/**
 * What eXo can say about a forward of the caller's own mailbox, read from the mail server
 * and never written by eXo.
 */
public enum ForwardingState {
  /** The server forwards nothing: no forward is set, or no script eXo can see may send one. */
  NONE,
  /** The server forwards mail to the destinations it names (BlueMind's {@code _forwarding}). */
  SERVER_FORWARD,
  /**
   * A script another client manages holds a {@code redirect}, or cannot be read far enough
   * to establish that it holds none: a forward may be configured by it. Its destinations
   * are never read out of it.
   */
  MAY_FORWARD_BY_SCRIPT,
  /** Nothing could be established: the engine cannot read a forward, or the read failed. */
  UNKNOWN
}
