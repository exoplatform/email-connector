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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A mailbox somebody shared with the user, as an agent sees it (EXO-90555): whose it
 * is, the name to pass as {@code mailbox} to the email tools, what the user may do in
 * it, and which of its folders are available to read.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SharedMailboxModel {

  /** The owner's name, as the platform shows it. */
  @JsonProperty("owner_name")
  private String       ownerName;

  /** The shared mailbox's address: the value to pass as {@code mailbox}. */
  private String       mailbox;

  /** The owner's eXo username, also accepted as {@code mailbox}. */
  @JsonProperty("owner_username")
  private String       ownerUsername;

  /** The access the owner granted: READER, EDITOR or CUSTOM. */
  private String       access;

  /** Unread messages in the shared inbox, as the user's mirror counts them. */
  @JsonProperty("unread_count")
  private int          unreadCount;

  /** The folders available to read: INBOX, SENT, ARCHIVE, those the mirror holds. */
  private List<String> folders;

  /** Whether a mail sent from this mailbox is also filed in the owner's Sent folder. */
  @JsonProperty("sent_copy")
  private boolean      sentCopy;

  /**
   * The {@code identity} values a mail from this mailbox may go out under besides
   * {@code me} (EXO-90585): {@code owner_on_behalf} and/or {@code owner}, empty when the
   * user may write in their own name only.
   */
  @JsonProperty("send_modes")
  private List<String> sendModes;
}
