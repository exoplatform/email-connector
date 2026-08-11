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
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Email {

  private Long                 id;

  private Long                 mailRemoteId;

  private String               mailHeaderId;

  private String               userId;

  private String               userEmail;

  private String               subject;

  private EmailContent         content;

  private Date                 receivedDate;

  private EmailSender          sender;

  private boolean              read;

  private boolean              recent;

  private List<EmailRecipient> to;

  private List<EmailRecipient> cc;

  private List<EmailRecipient> bcc;

  private List<EmailRecipient> replyTo;

  private List<Long>           categoryIds;

  private List<EmailOutgoingAttachment> attachments;

  // The conversation this message belongs to; the client groups the flat list into
  // threads by this id. inReplyTo / mailReferences stay backend-only.
  private String               threadId;

  @JsonIgnore
  private String               inReplyTo;

  @JsonIgnore
  private String               mailReferences;

  // The remote folder this message belongs to (INBOX / SENT / ARCHIVE). Exposed so
  // the client can scope the list to the inbox while the reader spans all folders.
  private String               folder;
}
