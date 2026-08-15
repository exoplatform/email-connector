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
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One message of a conversation, as somebody reading the whole conversation at once
 * needs it.
 * <p>
 * Thinner than {@link EmailModel} in the two places that matter when twenty-five of
 * these travel together. The body is plain text and truncated, because a conversation's
 * worth of HTML mail is mostly quoted history and signatures; and the attachments are
 * NAMES rather than descriptors, because "the contract" tells a reader what the
 * conversation is about, while a MIME part path and a download URL are things to act
 * on — and this is a read, not a way in.
 * <p>
 * The sender is flattened to a name and an address rather than carried as an object,
 * so that a message can be attributed by whichever of the two it actually has: a good
 * deal of mail arrives with no display name at all.
 * <p>
 * What it is NOT thinner in is identity. A message read inside a conversation is the
 * one a reader then wants to do something with — reply to it, read the half of it that
 * was cut, list what came attached — and a message with no handle can only be found
 * again by guessing at its subject and its sender. So each message carries both ids the
 * toolset keys on, and the folder without which one of them means nothing: an IMAP UID
 * numbers a message within ONE folder, and a conversation reliably mixes INBOX and SENT
 * (EXO-89367).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(value = Include.NON_EMPTY)
public class EmailThreadMessageModel {

  /**
   * The cached message's local database id — what {@code get_email_by_id} takes. The
   * folder-independent handle of the two, and therefore the one to use to read a
   * truncated message in full whatever folder it sits in.
   */
  @JsonProperty("email_id")
  private Long         emailId;

  /**
   * The message's IMAP UID — what {@code reply_email}, {@code reply_all},
   * {@code archive_email}, {@code mark_read} and {@code list_attachments} take. Only
   * meaningful together with {@link #folder} below: those tools are INBOX-scoped, so a
   * UID read off a SENT message and passed to them names a DIFFERENT message.
   */
  @JsonProperty("mail_remote_id")
  private Long         mailRemoteId;

  /**
   * Which remote folder this message was cached from: INBOX, SENT, ARCHIVE or
   * ALL_MAIL. Sent alongside the UID rather than left implicit, because a UID with no
   * folder is the exact shape of the bug EXO-89367 fixed.
   */
  private String       folder;

  /** The sender's display name, when the message carried one. */
  @JsonProperty("sender_name")
  private String       senderName;

  @JsonProperty("sender_address")
  private String       senderAddress;

  @JsonProperty("received_date")
  private Date         receivedDate;

  private String       subject;

  /** The body as plain text, truncated — see the tool for the limit and why. */
  private String       body;

  /** What was attached, by name only. */
  @JsonProperty("attachment_names")
  private List<String> attachmentNames;
}
