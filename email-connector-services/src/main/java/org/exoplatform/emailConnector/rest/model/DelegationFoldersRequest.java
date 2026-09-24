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

import java.util.List;

import org.exoplatform.emailConnector.model.FolderAccessChange;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The body of an owner's "Folders and access" save (EXO-90556): the folders, named as the
 * owner's own session names them, and the access to give in each, in the order to write
 * them. The server checks every name against the owner's own folders and reads each
 * folder's role itself.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DelegationFoldersRequest {

  private List<FolderAccessChange> folders;
}
