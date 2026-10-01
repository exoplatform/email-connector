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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A mail sent with an Undo (EXO-90837): its draft is held, and goes at
 * {@code sendDate} unless its sender takes it back first.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UndoableSend {

  /** The draft's handle, which the Undo is addressed by. */
  private String draftLocalId;

  /** When the mail goes, epoch milliseconds, on the server's clock. */
  private long   sendDate;

  /** How long the Undo is offered, in seconds: the sender's preference when sending. */
  private int    delaySeconds;
}
