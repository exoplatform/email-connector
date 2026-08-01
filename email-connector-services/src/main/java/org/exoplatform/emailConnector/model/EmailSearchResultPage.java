/**
 * Copyright (C) 2025 eXo Platform SAS
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
 * A page of server-side search hits. {@code totalMatches} is the full match
 * count the IMAP SEARCH reported — free to return (the UIDs are already in
 * hand) and the only way the client can say "showing 20 of 1,234" when the
 * result list is capped to the newest messages.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailSearchResultPage {

  // The newest matches, newest first, capped to the requested limit.
  private List<EmailSearchResult> results;

  // How many messages matched in total on the server.
  private int                     totalMatches;
}
