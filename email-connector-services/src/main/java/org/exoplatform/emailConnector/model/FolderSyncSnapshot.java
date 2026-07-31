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
 * What a remote folder looked like the last time it was fully synchronized, as
 * seen at SELECT time: the four IMAP change signals plus the window size the sync
 * used. The next sync compares a cheap STATUS against this; when every signal
 * matches, nothing can have changed and the folder sync is skipped — measured at
 * 98% of a no-op sync (an 8.5 s window FETCH re-downloading 1000 envelopes,
 * headers and MIME structures to discover nothing new).
 * <ul>
 * <li>{@code uidValidity} — a change means the server renumbered the folder and
 * every cached UID is meaningless;</li>
 * <li>{@code uidNext} — a change means at least one message arrived (even if it
 * was deleted again before we looked, which keeps the arrival+deletion pair with
 * an unchanged message count detectable);</li>
 * <li>{@code messageCount} — with uidNext unchanged, a change means messages were
 * expunged;</li>
 * <li>{@code highestModSeq} — RFC 7162; a change means SOME metadata changed,
 * which is what makes read/unread flags flipped in another client detectable.
 * Zero or negative means the capture ran without CONDSTORE, and the skip is then
 * never taken — stale flags are a silent correctness hole, not an optimisation;</li>
 * <li>{@code windowSize} — the cache size the window was listed with; if an admin
 * grows it, the wider window must actually download even though the server did
 * not change.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FolderSyncSnapshot {

  private long uidValidity;

  private long uidNext;

  private long messageCount;

  private long highestModSeq;

  private int  windowSize;
}
