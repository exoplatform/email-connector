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
package org.exoplatform.emailConnector.entity;

import java.util.Date;

import org.hibernate.annotations.DynamicUpdate;

import io.meeds.common.persistence.PortableSequence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One mailbox delegation: the grantee's subscription to another user's mailbox, one row
 * per (grantee, connector preset, owner mailbox), whoever created the share on the
 * server. The ACL itself is never here -- the server is its only source of truth --
 * and neither is any credential: the owner grants with their own session, the grantee
 * reads with theirs.
 * <p>
 * Two writers cross on this row -- the owner's lifecycle (revoke) and the grantee's
 * (accept, decline, preferences, activity stamp) -- so it is {@link DynamicUpdate}: a
 * read-modify-save flushes the columns that changed and not a snapshot of the whole
 * row taken before the other side wrote.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@DynamicUpdate
@Entity(name = "EmailDelegationEntity")
@Table(name = "EMAIL_DELEGATION",
       uniqueConstraints = @UniqueConstraint(name = "UQ_EMAIL_DELEGATION",
                                             columnNames = { "GRANTEE_ID", "CONNECTOR_ID", "OWNER_MAILBOX" }))
public class EmailDelegationEntity {

  @Id
  @PortableSequence(name = "SEQ_EMAIL_DELEGATION_ID")
  @Column(name = "ID")
  private Long    id;

  // The grantee's eXo username: the row's viewer. Every read is by (id, granteeId) or
  // (id, ownerId), so a guessed id addresses nothing.
  @Column(name = "GRANTEE_ID")
  private String  granteeId;

  // Nullable: a share discovered on the server may belong to someone eXo cannot map.
  @Column(name = "OWNER_ID")
  private String  ownerId;

  // The server-side identifier of the owner's mailbox as the ACL names it, and the
  // join key to GETACL and to the Other Users namespace.
  @Column(name = "OWNER_MAILBOX")
  private String  ownerMailbox;

  // The identifier the grant was written for, kept so the owner can still revoke once
  // the grantee has disconnected their own mailbox from eXo (the identifier can no
  // longer be resolved from their setting then). Null on a server-discovered share.
  @Column(name = "GRANTEE_MAILBOX")
  private String  granteeMailbox;

  @Column(name = "CONNECTOR_ID")
  private Long    connectorId;

  // The Other Users path prefix on the grantee's side, resolved from NAMESPACE at accept.
  @Column(name = "REMOTE_ROOT")
  private String  remoteRoot;

  // READER / EDITOR / CUSTOM, see DelegationPreset.
  @Column(name = "PRESET")
  private String  preset;

  // The letters last observed on the server: a cache for the chrome, never the truth.
  @Column(name = "RIGHTS")
  private String  rights;

  // The server's own vocabulary as last observed (BlueMind verbs, letters elsewhere),
  // for display only; added by changeset 1.0.0-80.
  @Column(name = "NATIVE_RIGHTS")
  private String  nativeRights;

  // See DelegationStatus.
  @Column(name = "STATUS")
  private String  status;

  // EXO or SERVER, see DelegationOrigin.
  @Column(name = "ORIGIN")
  private String  origin;

  @Column(name = "BADGE_INCLUDED")
  private boolean badgeIncluded;

  @Column(name = "NOTIFY_NEW_MAIL")
  private boolean notifyNewMail;

  // When the grantee last opened this mailbox: the sync-tier signal, per delegation.
  @Column(name = "LAST_ACTIVITY_DATE")
  private Date    lastActivityDate;

  @Column(name = "LAST_RIGHTS_CHECK_DATE")
  private Date    lastRightsCheckDate;

  @Column(name = "INVITED_DATE")
  private Date    invitedDate;

  @Column(name = "RESPONDED_DATE")
  private Date    respondedDate;

  @Column(name = "REVOKED_DATE")
  private Date    revokedDate;

  @Column(name = "CREATED_DATE")
  private Date    createdDate;

  @Column(name = "UPDATED_DATE")
  private Date    updatedDate;

  // The roles eXo's grant wrote beside INBOX (comma list), MAILBOX for a per-mailbox
  // grant, null for a share written before EXO-90548 (INBOX only).
  @Column(name = "GRANTED_ROLES")
  private String  grantedRoles;

  // The owner's role-to-folder-name map as the grant resolved it on the owner's session
  // (JSON): a delegate may see no special-use attribute on a shared folder (Dovecot).
  @Column(name = "OWNER_ROLE_FOLDERS")
  private String  ownerRoleFolders;
}
