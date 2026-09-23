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
import java.util.Map;

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
