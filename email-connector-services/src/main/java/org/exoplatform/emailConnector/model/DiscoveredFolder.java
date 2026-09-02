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

import java.util.Set;

/**
 * One folder as a walk of the mailbox's IMAP folder list reported it -- the
 * protocol-level facts a classification needs and nothing else, so the classifier can
 * be exercised without a mail server behind it.
 *
 * @param fullName the IMAP full name, hierarchy included
 * @param displayName the last path segment, as the interface shows it
 * @param delimiter the server's hierarchy separator, null on a flat namespace
 * @param attributes the LIST attributes, as the server spelled them ({@code \Sent},
 *          {@code \Noselect}, ...)
 * @param subscribed whether the folder was in the subscribed listing
 * @param selectable whether the folder can be opened at all
 */
public record DiscoveredFolder(String fullName,
                               String displayName,
                               String delimiter,
                               Set<String> attributes,
                               boolean subscribed,
                               boolean selectable) {

  /**
   * Whether the server tagged this folder with a LIST attribute, compared the way the
   * protocol does -- case-insensitively.
   *
   * @param attribute the attribute, backslash included
   * @return true when the server sent it
   */
  public boolean hasAttribute(String attribute) {
    return attributes != null && attributes.stream().anyMatch(attribute::equalsIgnoreCase);
  }
}
