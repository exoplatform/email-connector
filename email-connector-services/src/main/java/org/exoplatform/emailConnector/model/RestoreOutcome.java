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

import java.util.List;

/**
 * What a restore out of the Trash or the Junk folder did: how many messages could not
 * be put back, and which ones went back to Sent rather than to the inbox.
 * <p>
 * The second value exists because a restore no longer has one destination. A
 * conversation deleted from eXo takes its sent messages along (EXO-89942, the rule
 * "the same result as the same action done in Gmail"), so the Trash holds the user's
 * own replies beside the mail they answered, and each one goes back where it came
 * from: a message the user sent to Sent, everything else to the inbox. The client
 * shows the restored rows in their destination before the server lists them again,
 * and it can only do that if it is told which destination each row took.
 *
 * @param failures how many of the requested messages could NOT be restored
 * @param restoredToSent the IMAP UIDs, within the hidden folder, of the messages that
 *          went back to Sent; the other restored ones went to the inbox
 */
public record RestoreOutcome(int failures, List<Long> restoredToSent) {
}
