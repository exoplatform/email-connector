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

import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(value = Include.NON_EMPTY)
public class EmailModel {

  @JsonProperty("email_id")
  private Long                 id;

  // IMAP UID of the message. Read tools surface it so the agent can chain it
  // into write tools (mark_read, reply_email, archive_email, delete_email...),
  // which all key off mailRemoteId rather than the local database id.
  @JsonProperty("mail_remote_id")
  private Long                 mailRemoteId;

  /**
   * The conversation this message belongs to, and the only way an agent ever gets a
   * thread_id to pass to {@code get_email_thread}. Carried here because that tool's
   * description promises it: a tool that names an id its own reads never return is a
   * tool nothing can chain (EXO-89372).
   */
  @JsonProperty("thread_id")
  private String               threadId;

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
}
