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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Where one user's vCard file import got to — the state the drawer polls while
 * the run happens off the request thread, and the report it shows when the run
 * is done.
 * <p>
 * Stored under its own settings key, for the same reason the address-book sync
 * state is: two features rewriting one JSON document quietly lose each other's
 * fields. The four counters are the import's whole contract: every card in the
 * file lands in exactly one of them, so their sum says how far the run got and
 * their split says what a re-import would be a no-op about.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ContactImportState {

  /**
   * How the run is going, in the same vocabulary as the syncs: IN_PROGRESS
   * while the file is being walked, SUCCESS when it ended (even early, at the
   * card cap — the report then says so in {@link #messageCode}), FAILURE when
   * the run itself broke. Null when no import ever ran.
   */
  private SyncStatus status;

  /** Cards that became a contact. */
  private long       imported;

  /**
   * Cards skipped because one of their addresses already resolves to a contact
   * here — including a suppressed tombstone, which a bulk import must never
   * resurrect. Re-importing the same file lands everything in this counter.
   */
  private long       alreadyKnown;

  /**
   * Cards skipped for carrying no email address at all: with nothing to key
   * them on, such rows would duplicate on every re-import.
   */
  private long       noAddress;

  /** Cards the parser could not turn into fields. */
  private long       unreadable;

  /**
   * The message code of whatever cut the run short — the card cap, or what a
   * FAILURE was about. Null for a run that read its whole file.
   */
  private String     messageCode;

  /** When the run started, for display and to spot a run that hung. */
  private Long       startedDate;

  /** When the run ended, null while it is still going. */
  private Long       finishedDate;
}
