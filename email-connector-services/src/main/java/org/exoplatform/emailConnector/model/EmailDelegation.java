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

import java.util.Date;
import java.util.EnumMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One mailbox delegation as the service layer sees it: the row of
 * {@code EMAIL_DELEGATION}, one per (grantee, connector preset, owner mailbox). The row
 * is the grantee's subscription and its state; the ACL itself lives on the mail server
 * and is never stored -- {@link #rights} is the last observation of it, a cache for the
 * interface's chrome, never the source of truth.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailDelegation {

  private Long             id;

  /** The grantee's eXo username -- the row's viewer. */
  private String           granteeId;

  /** The owner's eXo username; null when a server-discovered share belongs to nobody eXo knows. */
  private String           ownerId;

  /** The server-side identifier of the owner's mailbox, as the ACL and the namespace name it. */
  private String           ownerMailbox;

  /**
   * The server-side identifier the grant was written for. Kept on the row so the owner
   * can revoke it after the grantee disconnected their own mailbox from eXo; null on a
   * server-discovered share whose identifier eXo did not write.
   */
  private String           granteeMailbox;

  /** The connector preset both sides are on; a delegation never crosses servers. */
  private Long             connectorId;

  /** The Other Users path of the owner's mailbox on the grantee's side, once resolved. */
  private String           remoteRoot;

  private DelegationPreset preset;

  /** The letters as last observed on the server, canonical order. */
  private String           rights;

  /**
   * The server's own vocabulary as last observed, for display: the verb list on
   * BlueMind ({@code Read, Freebusy, Invitation}), the letter string elsewhere. The
   * letters above drive the logic; this keeps what a lossy translation would lose.
   */
  private String           nativeRights;

  private DelegationStatus status;

  private DelegationOrigin origin;

  private boolean          badgeIncluded;

  private boolean          notifyNewMail;

  private Date             lastActivityDate;

  private Date             lastRightsCheckDate;

  private Date             invitedDate;

  private Date             respondedDate;

  private Date             revokedDate;

  private Date             createdDate;

  private Date             updatedDate;

  /**
   * The roles eXo's grant wrote beside INBOX (EXO-90548), {@code MAILBOX} for a
   * per-mailbox grant, null for a share written before (INBOX only).
   */
  private String           grantedRoles;

  /**
   * The owner's role-to-folder-name map as the grant resolved it on the owner's session
   * (EXO-90548). Server-side only: the delegate's discovery reads it, no screen does.
   * Never null: empty when no grant recorded one.
   */
  @JsonIgnore
  private Map<FolderRole, String> ownerRoleFolders = new EnumMap<>(FolderRole.class);

  /**
   * The delegation as it was before EXO-90548 recorded what a grant covered: every
   * existing positional caller keeps building it this way.
   *
   * @param id the row id
   * @param granteeId the grantee's username
   * @param ownerId the owner's username, when an eXo user
   * @param ownerMailbox the owner's mailbox identifier
   * @param granteeMailbox the grantee's mailbox identifier
   * @param connectorId the connector preset
   * @param remoteRoot the shared root in the grantee's listing
   * @param preset the preset
   * @param rights the letters last observed
   * @param nativeRights the server's own vocabulary last observed
   * @param status the status
   * @param origin who wrote the share
   * @param badgeIncluded whether it counts in the badge
   * @param notifyNewMail whether new mail notifies
   * @param lastActivityDate the grantee's last use
   * @param lastRightsCheckDate when the rights were last read
   * @param invitedDate when invited
   * @param respondedDate when answered
   * @param revokedDate when revoked
   * @param createdDate when created
   * @param updatedDate when updated
   */
  public EmailDelegation(Long id,
                         String granteeId,
                         String ownerId,
                         String ownerMailbox,
                         String granteeMailbox,
                         Long connectorId,
                         String remoteRoot,
                         DelegationPreset preset,
                         String rights,
                         String nativeRights,
                         DelegationStatus status,
                         DelegationOrigin origin,
                         boolean badgeIncluded,
                         boolean notifyNewMail,
                         Date lastActivityDate,
                         Date lastRightsCheckDate,
                         Date invitedDate,
                         Date respondedDate,
                         Date revokedDate,
                         Date createdDate,
                         Date updatedDate) {
    this(id, granteeId, ownerId, ownerMailbox, granteeMailbox, connectorId, remoteRoot, preset, rights, nativeRights, status, origin,
         badgeIncluded, notifyNewMail, lastActivityDate, lastRightsCheckDate, invitedDate, respondedDate, revokedDate, createdDate,
         updatedDate, null, new EnumMap<>(FolderRole.class));
  }

  /**
   * The rights, as a model.
   *
   * @return the last observed rights, empty when never observed
   */
  public MailboxRights getMailboxRights() {
    return MailboxRights.of(rights);
  }

  /**
   * The affordances of the last observed rights, for the interface. Serialised with the
   * row so a client never reads a letter.
   *
   * @return the affordances, see {@link MailboxRights#affordances()}
   */
  public Map<String, Boolean> getAffordances() {
    return getMailboxRights().affordances();
  }
}
