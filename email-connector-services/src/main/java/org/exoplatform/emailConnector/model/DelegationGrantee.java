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

import java.util.Map;

/**
 * One grantee of the caller's own mailbox, as the owner's sharing section lists them:
 * the server's ACL entry, mapped to an eXo user when one is connected on the same
 * preset with that identifier, and to the delegation row when one exists.
 *
 * @param identifier the server-side identifier the ACL names
 * @param granteeId the eXo username the identifier resolved to, null when nobody eXo
 *          knows holds it ("not an eXo user")
 * @param delegation the grantee's row, null for an identifier eXo cannot map -- such an
 *          entry has no id and cannot be acted on from eXo in this phase
 * @param preset the preset the engine recognises in the entry, CUSTOM when neither
 * @param rights the letters as the server answered them
 * @param nativeRights the server's own vocabulary as observed -- the same letters on an
 *          IMAP engine, the verb list on BlueMind (see {@link MailboxAce#nativeRights()})
 * @param affordances the controls the letters unlock, see {@link MailboxRights#affordances()}
 */
public record DelegationGrantee(String identifier,
                                String granteeId,
                                EmailDelegation delegation,
                                DelegationPreset preset,
                                String rights,
                                String nativeRights,
                                Map<String, Boolean> affordances) {

  /**
   * Builds an entry from an ACL entry and what eXo knows about it.
   *
   * @param ace the server's entry
   * @param granteeId the mapped eXo username, or null
   * @param delegation the row, or null
   * @return the entry
   */
  public static DelegationGrantee of(MailboxAce ace, String granteeId, EmailDelegation delegation) {
    return new DelegationGrantee(ace.identifier(),
                                 granteeId,
                                 delegation,
                                 ace.preset(),
                                 ace.rights().letters(),
                                 ace.nativeRights(),
                                 ace.rights().affordances());
  }
}
