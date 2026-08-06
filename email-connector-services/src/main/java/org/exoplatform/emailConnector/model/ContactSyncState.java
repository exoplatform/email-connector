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
 * Where one user's address-book sync got to: what was discovered, what version was
 * last seen whole, and how the last run went.
 * <p>
 * Stored under its own settings key rather than inside {@code userEmailSetting},
 * which would have been the obvious place. The mailbox sync rewrites that whole
 * JSON document every time it updates its status, so a contact sync writing the
 * same key would have the two quietly overwriting each other's fields — the kind
 * of loss that shows up as a setting that "sometimes doesn't stick".
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ContactSyncState {

  /**
   * The address book's absolute URL, resolved once by discovery so that later runs
   * cost no discovery at all. Cleared when the server stops recognising it, which
   * is the signal to discover again.
   */
  private String     addressBookHref;

  /**
   * The collection version of the last run that completed in full.
   * <p>
   * Only advanced when nothing was missed: a run that failed part way keeps the
   * old value, so the next run looks at everything again. Advancing it after a
   * partial run would skip the contacts that were missed, permanently and
   * silently.
   */
  private String     ctag;

  /** How the last run ended, using the same vocabulary as the mailbox sync. */
  private SyncStatus status;

  /** Consecutive failures, which is what escalates a bad password to BLOCKED. */
  private int        failedAttempts;

  /** When the last run started, both for display and to spot a run that hung. */
  private Long       lastSyncStartDate;
}
