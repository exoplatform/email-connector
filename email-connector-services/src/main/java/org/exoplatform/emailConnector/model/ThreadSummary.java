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

import java.util.List;

/**
 * What the list needs to know about a conversation it is only showing one message
 * of: how many messages the conversation holds in total, whether one of them is a
 * draft the user has not sent, and — for the conversations that do carry one — who
 * the conversation is with.
 * <p>
 * Every one of those facts is cross-folder, and that is why none of them can be
 * worked out from the listing itself. The list is built from the cached rows of ONE
 * folder, while a conversation's messages are spread over INBOX, SENT, ARCHIVE and
 * — since drafts became rows — DRAFTS. An inbox listing therefore never holds the
 * draft it has to report, a Drafts listing never holds the people the draft is a
 * reply to, and asking per row would be a query per visible conversation.
 * <p>
 * They travel together as one record because the list reads them together on the
 * same row: the participants, then the marker, then the count. Splitting them into
 * separate maps would let them drift apart — a count that includes a draft the flag
 * does not report, or a name the marker does not sit beside, is worse than either
 * fact alone. That they are gathered by two queries rather than one is a fact about
 * cost (see {@code EmailBoxStorage#getThreadSummaries}), deliberately not visible
 * from here.
 *
 * @param threadId the conversation id
 * @param messageCount how many distinct messages the conversation holds across
 *          every cached folder, the unsent draft included — the number the list
 *          shows next to the participants, Gmail-style
 * @param hasDraft whether the conversation carries a draft the user has not sent
 * @param participants who the conversation is with — the senders of its messages,
 *          oldest first, WITHOUT the account owner, which is what a draft row is
 *          labelled with in place of its own sender. Empty for a draft that answers
 *          nothing (the row then reads "Draft" and nothing else, as Gmail's does),
 *          and empty on every conversation that carries no draft, since nothing
 *          reads it there and gathering it would cost the whole cache
 */
public record ThreadSummary(String threadId, int messageCount, boolean hasDraft, List<String> participants) {
}
