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
package org.exoplatform.emailConnector.event;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.exoplatform.emailConnector.service.filters.FilterRunContext;

/**
 * Published by the sync, synchronously, right after it cached new mail of an inbox and
 * before it announces that mail: the moment eXo's own rules run. A rule that files a
 * mail away adds its UID to {@link #getFiledUids()}, and the sync leaves those out of
 * the new-mail announcement, so nothing downstream -- the assistant's categorisation,
 * the notification -- acts on mail the owner's rules already put away.
 * <p>
 * An event rather than a call, so the mailbox service knows nothing of the rules that
 * act on it through its own methods: no bean cycle.
 */
public class NewInboxMailEvent {

  private final String                username;

  private final FilterRunContext      context;

  private final List<InboxMail>       mails;

  private final Set<Long>             filedUids = new HashSet<>();

  /**
   * An inbox's new mail.
   *
   * @param username the mailbox's owner
   * @param context which mailbox it is
   * @param mails the mails just cached, with what the sync read of them
   */
  public NewInboxMailEvent(String username, FilterRunContext context, List<InboxMail> mails) {
    this.username = username;
    this.context = context;
    this.mails = mails == null ? List.of() : List.copyOf(mails);
  }

  /**
   * What the sync read of one mail it just cached, beyond its cached row: nothing here
   * is stored. The keywords come with the flags the sync fetched anyway; a header or the
   * size is read from the open message only when a rule asks for it.
   *
   * @param uid the mail's UID in the inbox
   * @param keywords the keywords the server set on it
   * @param headers reads the values of one of its headers
   * @param sizeKb reads its size, in kilobytes
   */
  public record InboxMail(long uid, Set<String> keywords, Function<String, List<String>> headers, Supplier<Long> sizeKb) {
  }

  /**
   * The mailbox's owner.
   *
   * @return the username
   */
  public String getUsername() {
    return username;
  }

  /**
   * Which mailbox it is.
   *
   * @return the context
   */
  public FilterRunContext getContext() {
    return context;
  }

  /**
   * The mails just cached.
   *
   * @return the mails
   */
  public List<InboxMail> getMails() {
    return mails;
  }

  /**
   * The UIDs of the mails a rule filed away, to leave out of the announcement.
   *
   * @return the UIDs, modifiable by the listener
   */
  public Set<Long> getFiledUids() {
    return filedUids;
  }
}
