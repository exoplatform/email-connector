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

/**
 * What the list needs to know about a conversation it is only showing one message
 * of: how many messages the conversation holds in total, and whether one of them is
 * a draft the user has not sent.
 * <p>
 * Both facts are cross-folder, and that is why they cannot be worked out from the
 * listing itself. The list is built from the cached rows of ONE folder, while a
 * conversation's messages are spread over INBOX, SENT, ARCHIVE and — since drafts
 * became rows — DRAFTS. An inbox listing therefore never holds the draft it has to
 * report, and asking per row would be a query per visible conversation.
 * <p>
 * The two facts travel together because they come out of one {@code GROUP BY} over
 * the same rows (see {@code EmailBoxDAO#summarizeThreadsByUserId}). Splitting them
 * into two maps would mean two scans of the same table to answer one listing, and
 * would let them drift apart — a count that includes a draft the flag does not
 * report is worse than either fact alone.
 *
 * @param threadId the conversation id
 * @param messageCount how many distinct messages the conversation holds across
 *          every cached folder, the unsent draft included — the number the list
 *          shows next to the participants, Gmail-style
 * @param hasDraft whether the conversation carries a draft the user has not sent
 */
public record ThreadSummary(String threadId, int messageCount, boolean hasDraft) {
}
