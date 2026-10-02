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
package org.exoplatform.emailConnector.model;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One mail of the owner's with a suggestion of an assistant still waiting for them, as
 * the mailbox's "Suggestions" view lists it (EXO-90851): the cached copy it opens, and
 * how many suggestions wait on it. Shaped like a search hit, so the list draws and opens
 * it as one. Built with setters only: no positional constructor to shift.
 */
@Data
@NoArgsConstructor
public class EmailWaitingSuggestionMail implements ListedMailRow {

  /** The local id of the cached row the view opens. */
  private Long        emailId;

  /** The message's IMAP UID, within its folder. */
  private Long        mailRemoteId;

  /** The folder the UID is numbered in. */
  private String      folder;

  /** The message's Message-ID, which its suggestions are attached to. */
  private String      mailHeaderId;

  /**
   * The conversation the message belongs to, as the folder list carries it: what the
   * reader reads the conversation by, rather than by the copy it opened (EXO-90875).
   */
  private String      threadId;

  private String      subject;

  private EmailSender sender;

  private Date        receivedDate;

  private boolean     read;

  private boolean     starred;

  /** Always true: the view lists cached copies only, openable at once. */
  private boolean     cached = true;

  /** How many suggestions wait on the message. */
  private int         waitingCount;

  /**
   * What the folder list's row carries of the cached message (EXO-90882): its excerpt and
   * its attachments, never its body. Left out of the answer when not set.
   */
  @JsonInclude(Include.NON_NULL)
  private EmailContent content;

  /** The size of the message's conversation, as the folder list counts it (EXO-90882). */
  @JsonInclude(Include.NON_NULL)
  private Integer     threadCount;

  /** Whether the message's conversation carries an unsent draft (EXO-90882). */
  @JsonInclude(Include.NON_NULL)
  private Boolean     threadHasDraft;
}
