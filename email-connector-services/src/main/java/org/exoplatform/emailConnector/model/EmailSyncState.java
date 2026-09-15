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

import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One mailbox's synchronization state, as the sync-state table holds it: the
 * claim ({@code syncStartedDate} and {@code claimedBy}), the cadence
 * ({@code lastSyncDate}, null when never synchronized) and the activity signal
 * ({@code lastActivityDate}, null when unknown). A flat copy of the row; nothing
 * here is derived.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailSyncState {

  private String userId;

  private Date   syncStartedDate;

  private String claimedBy;

  private Date   lastSyncDate;

  private Date   lastActivityDate;

  private Date   createdDate;
}
