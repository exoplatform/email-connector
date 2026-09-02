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
 * A snapshot of the mailbox sync dispatcher, for the administration drawer's
 * status line: what this node is doing right now, what the cluster holds, and how
 * far behind the whole thing is.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailSyncExecutorStatus {

  /** The node answering, as its claims name it. */
  private String node;

  /** Mailboxes being synchronized by this node right now. */
  private int    running;

  /** Mailboxes claimed by this node and waiting for a thread. */
  private int    queued;

  /** The executor's size on this node. */
  private int    threads;

  /** Mailboxes with a live claim, on every node. */
  private long   claimed;

  /** Mailboxes due and not yet claimed: the backlog. */
  private long   dueBacklog;

  /**
   * How long ago the due mailbox waiting longest was last synchronized (or
   * registered, if never), in minutes; zero when nothing is due.
   */
  private long   oldestDueMinutes;

  /** Mailboxes the dispatcher knows: one row each. */
  private long   connectedMailboxes;
}
