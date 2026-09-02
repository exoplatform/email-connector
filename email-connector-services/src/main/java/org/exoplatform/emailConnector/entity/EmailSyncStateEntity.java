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
package org.exoplatform.emailConnector.entity;

import java.util.Date;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The synchronization state of one connected mailbox: one row per user, keyed by
 * the username, and the only shared state the sync dispatcher needs.
 * <p>
 * The row replaces the per-user Quartz job the add-on used to register. A job
 * carried its cadence in a trigger and its "already running" in the scheduler's
 * memory; both are columns here, so that a dispatcher firing on every node of a
 * cluster with no coordination of its own can still agree, through the database,
 * on who synchronizes what. {@code SYNC_STARTED_DATE} is the CLAIM: non-null means
 * a node is syncing the mailbox (or died doing so, which is what the stale timeout
 * is for), and {@code CLAIMED_BY} names it. {@code LAST_SYNC_DATE} is the cadence,
 * the start time of the last completed run; null means never synced, which the
 * due-query puts first. {@code LAST_ACTIVITY_DATE} is the activity signal the
 * tiering reads; null means unknown.
 * <p>
 * Three writers share the row (the claim, the release, the activity stamp) and
 * each writes through a targeted UPDATE that names its own columns -- see
 * {@code EmailSyncStateDAO}. {@link DynamicUpdate} is the second line of defence,
 * for the one save that goes through the entity (its creation).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@DynamicUpdate
@Entity(name = "EmailSyncStateEntity")
@Table(name = "EMAIL_SYNC_STATE")
public class EmailSyncStateEntity {

  // The mailbox owner: the settings Context.USER id, the same string every other
  // per-user row of this add-on carries. A natural key, so no sequence to create
  // in the same breath as the table.
  @Id
  @Column(name = "USER_ID")
  private String userId;

  // The claim. Non-null while a node syncs this mailbox; a value older than the
  // stale timeout is reclaimable by anyone, which is how a crashed node's mailboxes
  // come back.
  @Column(name = "SYNC_STARTED_DATE")
  private Date   syncStartedDate;

  // Who took the claim, for the restarted node's own recovery and for the eye.
  @Column(name = "CLAIMED_BY")
  private String claimedBy;

  // The START of the last completed run, not its end: cadence-preserving like a
  // period trigger, and a run longer than its period is due again at once.
  @Column(name = "LAST_SYNC_DATE")
  private Date   lastSyncDate;

  // When the owner last opened their mailbox (or asked for a sync). The tiering's
  // only input; null is "unknown", which the due-query treats as inactive.
  @Column(name = "LAST_ACTIVITY_DATE")
  private Date   lastActivityDate;

  @Column(name = "CREATED_DATE", nullable = false)
  private Date   createdDate;
}
