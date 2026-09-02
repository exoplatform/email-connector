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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The user's folder list with the two numbers the settings screen states beside it:
 * how many custom folders they may mirror, and how deep each mirror goes. Both come
 * from the server rather than being repeated in the client, because both are
 * deployment-tunable and a hint that says "50" on a server set to 20 would be a lie.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MailFolderList {

  private List<MailFolderView> folders;

  private int                  maxCustomFolders;

  private int                  enabledCustomFolders;

  private int                  windowSize;
}
