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

/**
 * A stored conversation summary as a reader gets it: the words, whether the
 * conversation has moved on since they were written, and when they were written.
 * <p>
 * {@code stale} is not a stored column and deliberately so. It is computed on the way
 * out, by comparing the conversation as it stands NOW against the fingerprint the
 * summary was written with — which is what lets this whole feature work without a
 * scheduled job, an invalidation hook, or anything that has to remember to run when a
 * message arrives. Nothing writes "this is now out of date" anywhere; the question is
 * simply asked at the only moment its answer matters.
 * <p>
 * The fingerprint itself (the message count and the newest message's key) stays out of
 * this record. It is how the answer is arrived at, not part of the answer, and a
 * client shown an IMAP UID would sooner or later be tempted to do arithmetic with it.
 *
 * @param summary the written summary
 * @param stale whether the conversation has gained a message, or a different one is
 *          now its newest, since the summary was written
 * @param generatedDate when it was written
 */
public record ThreadAiSummary(String summary, boolean stale, Date generatedDate) {
}
