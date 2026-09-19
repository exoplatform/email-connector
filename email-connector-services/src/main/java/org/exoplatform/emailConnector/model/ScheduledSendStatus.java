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

/**
 * Where a scheduled mail stands, as the {@code EMAIL_SCHEDULED_SEND.STATUS} column
 * holds it. The transitions are the at-most-once guarantee of the feature, and each
 * one is a conditional UPDATE in {@code EmailScheduledSendDAO}:
 * <ul>
 * <li>{@link #SCHEDULED} to {@link #SENDING}: the claim, by the dispatcher (due rows
 * only) or by the owner's "send now" (from SCHEDULED, FAILED or UNCERTAIN);</li>
 * <li>{@link #SENDING} to {@link #SENT}: the mail server accepted the message;</li>
 * <li>{@link #SENDING} to {@link #FAILED}: refused before anything was accepted;</li>
 * <li>{@link #SENDING} to {@link #SCHEDULED}: ONLY when the connection to the mail
 * server could not even be opened, so nothing can have been transmitted;</li>
 * <li>{@link #SENDING} to {@link #UNCERTAIN}: anything else -- a failure once the
 * message was on its way, or a node that died holding the claim.</li>
 * </ul>
 * An UNCERTAIN mail is never sent again without its owner asking.
 */
public enum ScheduledSendStatus {

  /** Waiting for its date, or for its next attempt after a network failure. */
  SCHEDULED,

  /** Claimed: one node is transmitting it right now. */
  SENDING,

  /** Refused for good: the owner decides (retry, edit, back to Drafts). */
  FAILED,

  /** May have reached the mail server: never retried without the owner. */
  UNCERTAIN,

  /** Transmitted; its draft row is about to be removed. Never listed. */
  SENT
}
