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

import java.time.LocalDate;

import org.apache.commons.lang3.StringUtils;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What a mailbox search asks for, every criterion optional and all of them combined with
 * AND (EXO-90838). The same criteria are applied on the mail server for the user's own
 * folders and in eXo's copy for a folder of a mailbox shared with the user.
 * <p>
 * Built with setters, never positionally: most of the fields share a type.
 */
@Data
@NoArgsConstructor
public class EmailSearchCriteria {

  // Free text matched against the subject or the sender: the search box's own text.
  private String    query;

  // Text matched against the sender only, name or address.
  private String    from;

  // Text matched against the To or Cc recipients only, name or address.
  private String    to;

  // Text matched against the subject or the body.
  private String    words;

  // Only the messages not read yet.
  private boolean   unreadOnly;

  // Only the starred messages (\Flagged).
  private boolean   favoritesOnly;

  // Only the messages carrying a file attached.
  private boolean   attachmentsOnly;

  // Only the messages received in the last N days.
  private Integer   sinceDays;

  // Only the messages received on that day or later.
  private LocalDate after;

  // Only the messages received before that day, that day excluded.
  private LocalDate before;

  /**
   * Whether at least one criterion is set: a search with none would list the whole
   * folder, which is what the folder's own list is for.
   *
   * @return true when something narrows the search
   */
  public boolean hasCriterion() {
    return StringUtils.isNotBlank(query) || StringUtils.isNotBlank(from) || StringUtils.isNotBlank(to)
        || StringUtils.isNotBlank(words) || unreadOnly || favoritesOnly || attachmentsOnly || sinceDays != null
        || after != null || before != null;
  }
}
