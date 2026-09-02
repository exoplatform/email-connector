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
import java.util.Map;

/**
 * What one walk of the mailbox's folder list amounts to, once classified: which
 * physical folder plays each built-in role, and which folders are the user's own.
 * Folders the walk ignored (unselectable parents, Gmail's virtual Starred and
 * Important views) appear in neither.
 *
 * @param builtIns the built-in folders found, keyed by their {@link MailFolder}
 *          constant; a role the mailbox does not fill is absent
 * @param customs the selectable folders that are none of the built-ins, in listing
 *          order
 */
public record FolderClassification(Map<String, DiscoveredFolder> builtIns, List<DiscoveredFolder> customs) {

  /**
   * The folder playing a built-in role, if the mailbox has one.
   *
   * @param key the {@link MailFolder} constant
   * @return the folder, or null when the mailbox has none
   */
  public DiscoveredFolder builtIn(String key) {
    return builtIns == null ? null : builtIns.get(key);
  }
}
