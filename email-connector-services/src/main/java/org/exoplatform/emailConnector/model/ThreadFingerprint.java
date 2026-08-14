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
 * What a conversation looks like from the outside, in two values small enough to store
 * beside something written about it and compare later.
 * <p>
 * Both are about the conversation's REAL mail. Drafts are excluded from each of them,
 * for one reason stated twice: an unsent reply is re-dated and re-saved on every
 * keystroke, so a fingerprint that noticed it would change while nothing about the
 * conversation had; and it is the one message in the conversation that must never be
 * described back to the person still writing it.
 *
 * @param newestMessageKey the newest message as {@code FOLDER:UID} — an identity
 *          rather than a date, since two messages can share a date and a UID is what a
 *          message cannot be without. Null on a conversation with no real mail in it
 *          (one holding nothing but an unsent draft), which is exactly the case where
 *          there is nothing to summarise
 * @param messageCount how many distinct messages the conversation holds, counted by
 *          Message-ID as the reader counts them, so a message cached in two folders
 *          is one message rather than a conversation that has grown
 */
public record ThreadFingerprint(String newestMessageKey, int messageCount) {
}
