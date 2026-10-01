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
package org.exoplatform.emailConnector.event;

import java.util.List;

/**
 * Raised when a user renamed or moved one of their folders from eXo, after the server
 * and the registry hold the new names (EXO-90839): the folder and the folders inside it
 * kept their eXo keys, and their full names on the server changed.
 */
public class CustomFoldersRelocatedEvent {

  private final String       username;

  private final List<String> folderKeys;

  /**
   * @param username the mailbox owner
   * @param folderKeys the eXo keys of the folders whose full names changed
   */
  public CustomFoldersRelocatedEvent(String username, List<String> folderKeys) {
    this.username = username;
    this.folderKeys = List.copyOf(folderKeys);
  }

  /**
   * @return the mailbox owner
   */
  public String getUsername() {
    return username;
  }

  /**
   * @return the eXo keys of the folders whose full names changed
   */
  public List<String> getFolderKeys() {
    return folderKeys;
  }
}
