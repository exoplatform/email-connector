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

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One hit of a server-side mailbox search: just enough to render a result row,
 * deliberately NO body — the envelope comes back in the search's single batched
 * FETCH, while a body is a per-message round-trip the result list must never
 * pay. {@code cached} tells the client how to open the hit: a cached message
 * goes through the ordinary reader path instantly, an uncached one (outside the
 * local sync window) must first be pulled on demand via the fetch endpoint.
 */
@Data
@NoArgsConstructor
public class EmailSearchResult implements ListedMailRow {

  // The message's IMAP UID in the searched folder. UIDs are per-folder, so it
  // only identifies the message together with the folder below.
  private Long        mailRemoteId;

  // The MailFolder discriminator the search ran over (INBOX / SENT / ARCHIVE).
  private String      folder;

  private String      subject;

  private EmailSender sender;

  private Date        receivedDate;

  private boolean     read;

  // Whether the message carries the mail server's \Flagged flag, i.e. the user
  // favorited it. It rides along free: the search already fetches FLAGS to read
  // \Seen above, so this costs no extra round-trip.
  private boolean     starred;

  // Whether the message is already in the local cache — i.e. openable without
  // another IMAP round-trip.
  private boolean     cached;

  // A short piece of the message around what was searched for, or its opening words
  // when the match was in the subject or the sender. Only a hit read from the local
  // cache can carry one: a hit found on the server is envelope-only, and fetching
  // each body to quote it would cost one round-trip per result.
  private String      excerpt;

  // The local id of the cached row holding the message, whenever there is one -- for a
  // hit found on the server as for one read from the local copy: the key an agent names
  // one mail by, which a UID, numbered per folder, is not. Null when it is not cached,
  // and on the unified search's hits, which open by UID and folder.
  private Long        emailId;

  // The share a hit read from a mailbox shared with the user belongs to (EXO-90554),
  // null for the user's own mail: what the mail drawer's mailbox= opening takes.
  private Long        delegationId;

  // That mailbox's owner, as the result card names them; null for the user's own mail.
  private String      ownerFullName;

  // What the folder list's row carries of the cached message (EXO-90882): its excerpt and
  // its attachments, never its body. Set, by name, on the mail drawer's hits that are in
  // the local copy only; left out of the answer otherwise, so a hit that has none does
  // not erase what a listed row of the same mail already shows.
  @JsonInclude(Include.NON_NULL)
  private EmailContent content;

  // The size of the cached message's conversation and whether it carries an unsent
  // draft, as the folder list counts them (EXO-90882); left out like the content above.
  @JsonInclude(Include.NON_NULL)
  private Integer     threadCount;

  @JsonInclude(Include.NON_NULL)
  private Boolean     threadHasDraft;

  /**
   * A hit named by its folder and UID only, with no local id, no share and no owner.
   *
   * @param mailRemoteId the UID in the searched folder
   * @param folder the folder searched
   * @param subject the subject
   * @param sender the sender
   * @param receivedDate the reception date
   * @param read whether it is read
   * @param starred whether it carries \Flagged
   * @param cached whether it is in the local cache
   * @param excerpt a piece of it around the match, or null
   */
  public EmailSearchResult(Long mailRemoteId,
                           String folder,
                           String subject,
                           EmailSender sender,
                           Date receivedDate,
                           boolean read,
                           boolean starred,
                           boolean cached,
                           String excerpt) {
    this(mailRemoteId, folder, subject, sender, receivedDate, read, starred, cached, excerpt, null);
  }

  /**
   * A hit with a local id, when it has one, and no share or owner: an agent's search
   * hit, which names a shared mailbox otherwise (EXO-90555).
   *
   * @param mailRemoteId the UID in the searched folder
   * @param folder the folder searched
   * @param subject the subject
   * @param sender the sender
   * @param receivedDate the reception date
   * @param read whether it is read
   * @param starred whether it carries \Flagged
   * @param cached whether it is in the local cache
   * @param excerpt a piece of it around the match, or null
   * @param emailId the cached row's local id, or null
   */
  public EmailSearchResult(Long mailRemoteId,
                           String folder,
                           String subject,
                           EmailSender sender,
                           Date receivedDate,
                           boolean read,
                           boolean starred,
                           boolean cached,
                           String excerpt,
                           Long emailId) {
    this(mailRemoteId, folder, subject, sender, receivedDate, read, starred, cached, excerpt, emailId, null, null);
  }

  /**
   * A hit as every search builds one: its envelope, its local id and, for a mailbox shared
   * with the user, that share and its owner. What the folder list's row carries beside
   * (content, threadCount, threadHasDraft) is set by name, by the mail drawer's searches
   * only.
   *
   * @param mailRemoteId the UID in the searched folder
   * @param folder the folder searched
   * @param subject the subject
   * @param sender the sender
   * @param receivedDate the reception date
   * @param read whether it is read
   * @param starred whether it carries \Flagged
   * @param cached whether it is in the local cache
   * @param excerpt a piece of it around the match, or null
   * @param emailId the cached row's local id, or null
   * @param delegationId the share the hit was read from, null for the user's own mail
   * @param ownerFullName the shared mailbox's owner, null for the user's own mail
   */
  public EmailSearchResult(Long mailRemoteId, // NOSONAR one parameter per field of the hit
                           String folder,
                           String subject,
                           EmailSender sender,
                           Date receivedDate,
                           boolean read,
                           boolean starred,
                           boolean cached,
                           String excerpt,
                           Long emailId,
                           Long delegationId,
                           String ownerFullName) {
    this.mailRemoteId = mailRemoteId;
    this.folder = folder;
    this.subject = subject;
    this.sender = sender;
    this.receivedDate = receivedDate;
    this.read = read;
    this.starred = starred;
    this.cached = cached;
    this.excerpt = excerpt;
    this.emailId = emailId;
    this.delegationId = delegationId;
    this.ownerFullName = ownerFullName;
  }
}
