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

import org.exoplatform.emailConnector.model.DelegationPreset;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The body of a share request: who, and which preset. The mailbox is never here -- it
 * is always the caller's own -- and the grantee is an eXo username the service resolves
 * server-side, never a mail login.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DelegationInviteRequest {

  private String           granteeUsername;

  private DelegationPreset preset;
}
