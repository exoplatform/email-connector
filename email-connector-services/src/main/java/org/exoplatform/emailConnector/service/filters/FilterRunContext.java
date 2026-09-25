/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.service.filters;

import org.exoplatform.emailConnector.model.EmailFilter;
import org.exoplatform.emailConnector.model.MailFolder;

/**
 * Which mailbox the mail a sync just cached belongs to. Only the rules of that mailbox's
 * scope run on it: today the owner's own inbox, and a shared inbox the day its rules
 * exist, never by accident the other way round.
 *
 * @param mailboxScope the scope of the mailbox, {@link EmailFilter#SCOPE_OWN} or a
 *          shared mailbox's
 * @param folder the folder the mail was cached in
 */
public record FilterRunContext(String mailboxScope, String folder) {

  /** The owner's own inbox: the one mailbox eXo rules run on in this phase. */
  public static final FilterRunContext OWN_INBOX = new FilterRunContext(EmailFilter.SCOPE_OWN, MailFolder.INBOX);

  /**
   * Whether eXo rules run on this mailbox at all in this phase.
   *
   * @return true for the owner's own inbox only
   */
  public boolean isOwnInbox() {
    return EmailFilter.SCOPE_OWN.equals(mailboxScope) && MailFolder.INBOX.equals(folder);
  }
}
