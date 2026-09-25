/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.service.bluemind;

import java.util.Set;

/**
 * BlueMind's {@code MailFilter.Forwarding}, member for member (core API javadoc
 * 5.0.7563: {@code boolean enabled; boolean localCopy; Set<String> emails}). Read only in
 * this add-on.
 *
 * @param enabled whether mail is forwarded
 * @param localCopy whether a copy is kept in the mailbox
 * @param emails the destinations, never null
 */
public record BlueMindForwarding(boolean enabled, boolean localCopy, Set<String> emails) {

  /**
   * Keeps the destinations unmodifiable and never null.
   *
   * @param enabled whether mail is forwarded
   * @param localCopy whether a copy is kept
   * @param emails the destinations
   */
  public BlueMindForwarding {
    emails = emails == null ? Set.of() : Set.copyOf(emails);
  }
}
