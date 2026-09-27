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

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One tool call a mail filter's assistant made and that was recorded instead of run: the
 * tool, as its definition named and described it when the call was made, and the
 * arguments exactly as the model gave them. Nothing runs until the mail's owner approves
 * it; the call then runs as the owner, through the platform's own tool path.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailFilterProposal {

  /** Waiting for the owner. */
  public static final String       PROPOSED      = "PROPOSED";

  /** Approved; the call is running. */
  public static final String       RUNNING       = "RUNNING";

  /** Approved and run. */
  public static final String       DONE          = "DONE";

  /** Approved, and the tool refused it or failed. */
  public static final String       FAILED        = "FAILED";

  /** Rejected by the owner. */
  public static final String       REJECTED      = "REJECTED";

  /** Left unanswered past its expiry, or superseded by a new run of the assistant. */
  public static final String       EXPIRED       = "EXPIRED";

  /** Handed to the regular AI chat, where the owner goes on; it never runs from here. */
  public static final String       HANDED_OVER   = "HANDED_OVER";

  /** Every status. */
  public static final List<String> STATUSES      = List.of(PROPOSED, RUNNING, DONE, FAILED, REJECTED, EXPIRED, HANDED_OVER);

  /** The reason of a proposal a new run of the assistant replaced. */
  public static final String       SUPERSEDED    = "emailConnector.filters.proposal.superseded";

  /** The proposal's id. */
  private Long                     id;

  /** The match whose assistant made the call. */
  private Long                     matchId;

  /** The rule of that match. */
  private Long                     filterId;

  /** The tool's name. */
  private String                   toolName;

  /** The tool's title, as its definition gave it when the call was made; may be null. */
  private String                   toolTitle;

  /** The tool's description, as its definition gave it when the call was made. */
  private String                   toolDescription;

  /** The call's arguments, the JSON the model gave. */
  private String                   arguments;

  /** The assistant's one-line reason, its own suggestion; may be null. */
  private String                   rationale;

  /** One of {@link #STATUSES}. */
  private String                   status;

  /** When the call was recorded, in milliseconds. */
  private Long                     createdDate;

  /** When it expires if left unanswered, in milliseconds. */
  private Long                     expiresDate;

  /** When the owner decided, in milliseconds; null while undecided. */
  private Long                     decidedDate;

  /** The assistant run's conversation. */
  private String                   conversationId;

  /** What the tool answered once run; null otherwise. */
  private String                   result;

  /** Why it did not run: the tool's message, or a message code. */
  private String                   lastError;
}
