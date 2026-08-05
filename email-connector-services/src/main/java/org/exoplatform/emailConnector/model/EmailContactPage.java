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
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One page of the contact list, with what the drawer's A–Z rail needs to jump
 * without loading everything: the letter-index map and the total. The map is
 * ORDERED exactly as the server sorts ("A".."Z" then "#"), so an offset for any
 * letter is the sum of the counts of the letters before it in iteration order.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailContactPage {

  private List<EmailContact> contacts;

  /** Ordered letter → count map over the whole filtered set, not just this page. */
  private Map<String, Long>  letterIndex;

  /** Total contacts matching the filter, across all pages. */
  private long               size;

  private long               offset;

  private long               limit;
}
