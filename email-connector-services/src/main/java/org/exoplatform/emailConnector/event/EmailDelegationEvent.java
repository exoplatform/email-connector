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

import org.exoplatform.emailConnector.model.EmailDelegation;

/**
 * A delegation changed state. Published in the same transaction as the change, so a
 * listener -- the notification glue the delegation plan's section 7.2 describes, the
 * analytics listener -- can react without the service knowing them. The listener
 * carries no business logic: it reads the row and delegates.
 *
 * @param type what happened
 * @param actor the username whose action it was
 * @param delegation the row as it now stands
 */
public record EmailDelegationEvent(Type type, String actor, EmailDelegation delegation) {

  /** The transitions a listener can tell apart. */
  public enum Type {
    /** The owner granted and invited. */
    INVITED,
    /** The grantee accepted, the server confirmed. */
    ACCEPTED,
    /** The grantee declined; the ACL stays. */
    DECLINED,
    /** The grantee left an accepted share; the ACL stays. */
    LEFT,
    /** The owner removed the access on the server. */
    REVOKED
  }
}
