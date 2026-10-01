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

import java.util.Date;
import java.util.List;

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
public class EmailSearchResultPage {

  // The newest matches, newest first, capped to the requested limit.
  private List<EmailSearchResult> results;

  // How many messages matched in total on the server.
  private int                     totalMatches;

  // Whether this page was narrowed to the user's favorites. It travels back so the
  // results can continue the same search on the mail server without being told twice.
  private boolean                 favoritesOnly;

  // For a search of eXo's copy of a folder (EXO-90838): the date of the copy's oldest
  // message in that folder, which bounds what such a search can find; null otherwise.
  private Date                    cachedSince;

  // For a search of the mail server narrowed to the messages with an attachment
  // (EXO-90838): how many of the newest matches were examined for one, when there were
  // more matches than that -- the count then covers those only; 0 when every match was.
  private int                     scanned;

  /**
   * A page of hits, as every search builds one; the two fields above are set by name
   * by the searches that have them.
   *
   * @param results the newest matching messages
   * @param totalMatches how many matched in total
   * @param favoritesOnly whether the page was narrowed to favorites
   */
  public EmailSearchResultPage(List<EmailSearchResult> results, int totalMatches, boolean favoritesOnly) {
    this.results = results;
    this.totalMatches = totalMatches;
    this.favoritesOnly = favoritesOnly;
  }

  /**
   * A page that was not narrowed to favorites, which is the ordinary case.
   *
   * @param results the newest matching messages
   * @param totalMatches how many matched in total
   */
  public EmailSearchResultPage(List<EmailSearchResult> results, int totalMatches) {
    this(results, totalMatches, false);
  }
}
