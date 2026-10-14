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
package org.exoplatform.emailConnector.event;

/** The transitions of a share a listener can tell apart ({@link EmailDelegationEvent#type()}). */
public enum EmailDelegationEventType {
  /** The owner granted and invited. */
  INVITED,
  /** The grantee accepted, the server confirmed. */
  ACCEPTED,
  /** The grantee declined; the ACL stays. */
  DECLINED,
  /** The grantee left an accepted share; the ACL stays. */
  LEFT,
  /** The owner removed the access on the server. */
  REVOKED,
  /** The grantee changed whether the share counts in their unread badge. */
  BADGE_PREFERENCE_CHANGED,
  /**
   * What the grantee may do changed without a lifecycle act: the owner changed the
   * preset and the right to keep read state moved, or a reconciliation found an
   * accepted share gone from the server. Nobody is notified; the grantee's badge is
   * re-counted.
   */
  RIGHTS_CHANGED
}
