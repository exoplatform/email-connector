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
 * One draft of the user's mailbox as the draft tools return it: what the Email app's
 * Drafts folder shows, plus the handles to act on it and to find it again once sent.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(value = Include.NON_EMPTY)
public class EmailDraftModel {

  /** The draft's id, to pass as draft_id to the other draft tools. */
  @JsonProperty("draft_id")
  private String       draftId;

  private List<String> to;

  private List<String> cc;

  private List<String> bcc;

  private String       subject;

  /** The body as plain text, cut when long: what the user would read. */
  private String       body;

  /**
   * The whole body as stored, HTML and signature included: what update_email_draft
   * starts from to change part of it. Given for one draft, and in a listing on request.
   */
  @JsonProperty("body_html")
  private String       bodyHtml;

  /**
   * The Message-ID the mail will carry once sent, whoever sends it: the handle to
   * recognise it in the Sent folder.
   */
  @JsonProperty("message_id")
  private String       messageId;

  /** The conversation the draft belongs to. */
  @JsonProperty("thread_id")
  private String       threadId;

  /** Whether the draft answers another mail, and stays in its conversation when sent. */
  private boolean      reply;

  @JsonProperty("last_modified")
  private Date         lastModified;

  /** Where the user opens their drafts in the Email app. */
  private String       link;
}
