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
 * A page of contact search hits, and how many contacts actually matched.
 * <p>
 * The count keeps the agent honest, exactly as it does for the mailbox search:
 * the list is hard-capped, so without the total an agent would answer "you have
 * 20 contacts at Acme" when the store holds two hundred. With it in hand it can
 * say what it saw and what it did not.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ContactSearchResultsModel {

  /** How many contacts matched the filter, however many are returned below. */
  @JsonProperty("total_matches")
  private long                  totalMatches;

  /** The matching contacts, alphabetical, first page only. */
  private List<ContactHitModel> results;
}
