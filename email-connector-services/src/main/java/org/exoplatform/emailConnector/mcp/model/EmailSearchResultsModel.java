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
package org.exoplatform.emailConnector.mcp.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A page of mailbox search hits, and how many messages actually matched.
 * <p>
 * The count is the point of this wrapper. A search reaches the whole mailbox
 * and returns only its newest hits, so handing back a bare list would let an
 * agent answer "you have 3 mails from Marie" when the server found ninety. With
 * the total in hand it can say what it saw and what it did not.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EmailSearchResultsModel {

  /** How many messages matched on the server, however many are returned below. */
  @JsonProperty("total_matches")
  private int                       totalMatches;

  /** The newest matches, newest first. */
  private List<EmailSearchHitModel> results;
}
