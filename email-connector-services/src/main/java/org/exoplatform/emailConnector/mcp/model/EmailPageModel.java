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
 * One page of a mailbox folder's mirror, with how many messages the mirror
 * holds for it (EXO-94363).
 * <p>
 * A bare list let an agent ask for a page as large as the mailbox and gave
 * it no total, so it read everything to count. With the count and whether
 * more remain, one page answers « how many » and the next one is asked for
 * only when needed.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EmailPageModel {

  /** The messages of this page, newest first. */
  private List<EmailModel> emails;

  /** How many messages of the folder were skipped before this page. */
  private int              offset;

  /** The most messages this page could hold, once capped. */
  private int              limit;

  /**
   * How many messages of the folder the mirror holds, the unread filter
   * applied: the mirror holds the latest ones only, so the folder may hold
   * more.
   */
  private int              count;

  /** Whether messages remain after this page. */
  @JsonProperty("has_more")
  private boolean          hasMore;
}
