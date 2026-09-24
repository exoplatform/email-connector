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

import java.util.List;

/**
 * The owner's sharing section in one answer: what the server can do, and who has
 * access. When sharing is unsupported the list holds eXo's own rows only, so the
 * interface can still show past invitations beside the reason.
 *
 * @param capabilities what the connected server can do about sharing
 * @param ownerMailbox the caller's own mailbox identifier, as the ACL names it
 * @param grantees who has access, server entries first
 * @param sentCopyEnabled whether an administrator left the owner's Sent copy of a
 *          delegate's mail on (EXO-90551): with the share's own Sent, what the consent to
 *          writing in the owner's name says about the owner keeping a copy (EXO-90582)
 */
public record GrantedDelegations(MailboxAclCapabilities capabilities,
                                 String ownerMailbox,
                                 List<DelegationGrantee> grantees,
                                 boolean sentCopyEnabled) {

  /**
   * The owner's sharing section with no Sent copy promised.
   *
   * @param capabilities what the connected server can do about sharing
   * @param ownerMailbox the caller's own mailbox identifier
   * @param grantees who has access
   */
  public GrantedDelegations(MailboxAclCapabilities capabilities, String ownerMailbox, List<DelegationGrantee> grantees) {
    this(capabilities, ownerMailbox, grantees, false);
  }
}
