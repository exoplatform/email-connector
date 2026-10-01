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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A user's choices about resources a received message would fetch from the internet
 * when displayed (EXO-90841): whether they are held back until asked for, and the
 * senders whose messages always load them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RemoteContentSettings {

  /** Whether remote resources wait for the user's consent; true unless the user switched it off. */
  private boolean      blockRemoteContent = true;

  /** The sender addresses, lower-cased, whose messages always load remote resources. */
  private List<String> trustedSenders;
}
