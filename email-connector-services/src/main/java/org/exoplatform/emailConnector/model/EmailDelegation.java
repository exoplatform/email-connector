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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

  /** {@link #grantedRoles} of a grant made in one call on a per-mailbox server. */
  public static final String GRANTED_WHOLE_MAILBOX = "MAILBOX";

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
   * Whether the grantee's unified search returns this shared mailbox's mail, labelled
   * with its owner (EXO-90554, PO decision Q-6): on by default, turned off per share.
   */
  private boolean                 searchIncluded   = true;

  /**
   * The owner's per-folder exceptions to the share's preset, on the role folders a grant
   * covers beside INBOX (EXO-90556): {@code TRASH=READER}, or {@link FolderAccess#NONE}
   * for a role folder the owner chose not to share. A role absent here follows the
   * preset. The owner's other folders are never recorded: the mail server's ACL is their
   * only truth, read when the owner opens the list. Never null.
   */
  private Map<FolderRole, FolderAccess> folderAccess = new EnumMap<>(FolderRole.class);

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
   * A delegation searched by default: {@code searchIncluded} true.
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
   * @param grantedRoles the roles eXo's grant wrote beside INBOX
   * @param ownerRoleFolders the owner's role-to-folder-name map
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
                         Date updatedDate,
                         String grantedRoles,
                         Map<FolderRole, String> ownerRoleFolders) {
    this(id, granteeId, ownerId, ownerMailbox, granteeMailbox, connectorId, remoteRoot, preset, rights, nativeRights, status, origin,
         badgeIncluded, notifyNewMail, lastActivityDate, lastRightsCheckDate, invitedDate, respondedDate, revokedDate, createdDate,
         updatedDate, grantedRoles, ownerRoleFolders, true, new EnumMap<>(FolderRole.class));
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

  /**
   * The roles eXo's grant wrote beside INBOX, parsed once (EXO-90548).
   *
   * @return the roles, empty for an INBOX-only share and for a per-mailbox grant
   */
  public Set<FolderRole> grantedRoleSet() {
    Set<FolderRole> roles = EnumSet.noneOf(FolderRole.class);
    if (grantedRoles == null || grantsWholeMailbox()) {
      return roles;
    }
    for (String name : grantedRoles.split(",")) {
      FolderRole role = FolderRole.of(name);
      if (role != null) {
        roles.add(role);
      }
    }
    return roles;
  }

  /**
   * Whether the grant was one call on a per-mailbox server (BlueMind), which covers every
   * folder of the mailbox at once.
   *
   * @return true for a per-mailbox grant
   */
  public boolean grantsWholeMailbox() {
    return GRANTED_WHOLE_MAILBOX.equals(grantedRoles);
  }

  /**
   * Whether the share was written before eXo granted more than INBOX (EXO-90548): what
   * offers the owner "Extend access".
   *
   * @return true for a share whose grant recorded nothing
   */
  public boolean isInboxOnly() {
    return grantedRoles == null;
  }

  /**
   * The owner's folders the grant found but could not share -- "Trash could not be
   * shared: your server refused", on the owner's list (EXO-90548).
   *
   * @return the roles, in grant order, empty when none failed
   */
  public List<FolderRole> getRolesNotShared() {
    if (grantedRoles == null || grantsWholeMailbox() || ownerRoleFolders == null) {
      return List.of();
    }
    Set<FolderRole> granted = grantedRoleSet();
    // A folder the owner chose not to share is not one the server refused (EXO-90556).
    return FolderRole.GRANTED.stream()
                             .filter(role -> ownerRoleFolders.containsKey(role) && !granted.contains(role))
                             .filter(role -> accessException(role) != FolderAccess.NONE)
                             .toList();
  }

  /**
   * The owner's exception for one role folder, null when it follows the preset
   * (EXO-90556).
   *
   * @param role the role
   * @return READER, EDITOR, NONE, or null
   */
  public FolderAccess accessException(FolderRole role) {
    return folderAccess == null || role == null ? null : folderAccess.get(role);
  }

  /**
   * The stored form of per-folder exceptions: {@code ROLE=ACCESS} pairs in grant order,
   * comma-separated; null when there is none, which is also how every share written
   * before EXO-90556 reads.
   *
   * @param access the exceptions
   * @return the stored form, or null
   */
  public static String folderAccessOf(Map<FolderRole, FolderAccess> access) {
    if (access == null || access.isEmpty()) {
      return null;
    }
    StringBuilder stored = new StringBuilder();
    for (FolderRole role : FolderRole.GRANTED) {
      FolderAccess value = access.get(role);
      if (value != null) {
        stored.append(stored.isEmpty() ? "" : ",").append(role.name()).append('=').append(value.name());
      }
    }
    return stored.isEmpty() ? null : stored.toString();
  }

  /**
   * The stored exceptions read back. A pair this version cannot read -- a role or an
   * access it does not know -- is skipped: that folder then follows the preset.
   *
   * @param stored the stored form
   * @return the exceptions, empty when none, never null
   */
  public static Map<FolderRole, FolderAccess> folderAccessFrom(String stored) {
    Map<FolderRole, FolderAccess> access = new EnumMap<>(FolderRole.class);
    if (stored == null || stored.isBlank()) {
      return access;
    }
    for (String pair : stored.split(",")) {
      int equals = pair.indexOf('=');
      if (equals <= 0) {
        continue;
      }
      FolderRole role = FolderRole.of(pair.substring(0, equals));
      FolderAccess value = FolderAccess.of(pair.substring(equals + 1));
      if (role != null && role != FolderRole.DRAFTS && value != null) {
        access.put(role, value);
      }
    }
    return access;
  }

  /**
   * Exceptions without the ones that say what the preset says anyway: a role set to the
   * share's own preset follows it again (EXO-90556, "Change access" keeps only real
   * exceptions).
   *
   * @param access the exceptions
   * @param preset the share's preset
   * @return the exceptions that still differ, never null
   */
  public static Map<FolderRole, FolderAccess> withoutPreset(Map<FolderRole, FolderAccess> access, DelegationPreset preset) {
    Map<FolderRole, FolderAccess> kept = new EnumMap<>(FolderRole.class);
    FolderAccess follows = FolderAccess.of(preset);
    if (access != null) {
      access.forEach((role, value) -> {
        if (role != null && value != null && value != follows) {
          kept.put(role, value);
        }
      });
    }
    return kept;
  }

  /**
   * The stored form of a set of granted roles: INBOX first, always -- so a grant that
   * shared INBOX alone is told from a share written before roles were recorded (null) --
   * then the roles in grant order.
   *
   * @param roles the roles granted beside INBOX
   * @return the comma list
   */
  public static String grantedRolesOf(Set<FolderRole> roles) {
    StringBuilder list = new StringBuilder(MailFolder.INBOX);
    for (FolderRole role : FolderRole.GRANTED) {
      if (roles != null && roles.contains(role)) {
        list.append(',').append(role.name());
      }
    }
    return list.toString();
  }
}
