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
package org.exoplatform.emailConnector.rest.model;

import java.util.Map;

import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.FolderAccess;
import org.exoplatform.emailConnector.model.FolderRole;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The body of a share request: who, which preset, and optionally the owner's choice for
 * the role folders (EXO-90556). The mailbox is never here -- it
 * is always the caller's own -- and the grantee is an eXo username the service resolves
 * server-side, never a mail login.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DelegationInviteRequest {

  private String                        granteeUsername;

  private DelegationPreset              preset;

  /**
   * The owner's choice for Sent, Archive, Trash and Spam, made before the share exists
   * (EXO-90556): a role absent follows the preset, NONE is never shared. Optional.
   */
  private Map<FolderRole, FolderAccess> folderAccess;

  /**
   * A share request with the preset on every folder the grant covers.
   *
   * @param granteeUsername the eXo user to share with
   * @param preset READER or EDITOR
   */
  public DelegationInviteRequest(String granteeUsername, DelegationPreset preset) {
    this(granteeUsername, preset, null);
  }
}
