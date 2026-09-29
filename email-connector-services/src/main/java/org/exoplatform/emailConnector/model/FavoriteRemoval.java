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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What removing a favorite did: the copies of its message the server was asked to
 * unstar, by the folder each is numbered in, and how many of them it could not. The
 * Favorites drawer tells an open mailbox which rows lost their star from this, one
 * folder at a time, so a copy filed under a label goes out with the inbox one.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteRemoval {

  // How many copies the mail server did not unstar; their local star was put back.
  private int                     failedUpdates;

  // The UIDs asked to be unstarred, keyed by the EMAIL_BOX.FOLDER discriminator they
  // are numbered in, the favorite's own folder last.
  private Map<String, List<Long>> unstarred;
}
