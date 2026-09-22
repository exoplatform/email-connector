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
 * Where a delegation stands, from the grantee's point of view -- the row is the
 * grantee's subscription, whoever created the share on the server.
 */
public enum DelegationStatus {

  /** Invited from eXo: the ACL is already on the server, the grantee has not answered. */
  PENDING,

  /** The grantee accepted and the server confirmed their rights; the mailbox is subscribed. */
  ACCEPTED,

  /**
   * The grantee said no, or left. The ACL on the server is untouched -- only the owner
   * removes it -- so the row can go back to ACCEPTED later.
   */
  DECLINED,

  /** The owner (or an administrator) removed the access; the ACL is gone. */
  REVOKED,

  /** The shared mailbox is no longer listed to the grantee: deleted, renamed, or hidden. */
  GONE,

  /** Seen on the server, granted outside eXo, nobody invited: offered, never auto-subscribed. */
  AVAILABLE
}
