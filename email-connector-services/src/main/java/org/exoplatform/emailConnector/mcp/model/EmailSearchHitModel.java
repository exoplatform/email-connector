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
package org.exoplatform.emailConnector.mcp.model;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.exoplatform.emailConnector.model.EmailSender;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One hit of a mailbox search: what the mail server answers about a message
 * without downloading it.
 * <p>
 * Deliberately thinner than {@link EmailModel}: a search reaches the whole
 * mailbox, so fetching the body of every hit would cost one round-trip per
 * message for mail the agent may never open. The agent gets the envelope, and
 * chains {@code mail_remote_id} into {@code get_email_full} for the one it
 * wants.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(value = Include.NON_EMPTY)
public class EmailSearchHitModel {

  /** IMAP UID of the message, to chain into the other tools. */
  @JsonProperty("mail_remote_id")
  private Long        mailRemoteId;

  /** The folder the hit was found in: INBOX, SENT or ARCHIVE. */
  private String      folder;

  private String      subject;

  private EmailSender sender;

  @JsonProperty("received_date")
  private Date        receivedDate;

  private boolean     read;

  /** Whether the user favorited the message (the server's \Flagged flag). */
  private boolean     starred;

  /**
   * Whether the message is also in the local mirror. A hit that is not cached
   * still opens, it is simply fetched from the server on demand.
   */
  private boolean     cached;
}
