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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The cached messages of each folder, counted twice in one read: all of them, and the
 * unread ones -- the numbers the folder list is built from, and the ones the
 * full-screen folder column shows beside the inbox and the spam (EXO-90415). Keyed by
 * the {@code EMAIL_BOX.FOLDER} discriminator; a folder holding no cached message has no
 * entry in either map.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FolderMessageCounts {

  // How many messages the cache holds per folder.
  private Map<String, Integer> messageCounts;

  // How many of them are unread, per folder: the local read flag, which mirrors the
  // server's \Seen and the reads made in the interface.
  private Map<String, Integer> unreadCounts;
}
