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
 * One match of one eXo rule on one mail: a line of the rule's log and of the mail's
 * Automations panel, the undo data of what eXo did, and the assistant's work item.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailFilterMatch {

  /** No assistant on this rule. */
  public static final String       AGENT_NONE             = "NONE";

  /** Waiting for the assistant. */
  public static final String       AGENT_PENDING          = "PENDING";

  /** The assistant is on it. */
  public static final String       AGENT_RUNNING          = "RUNNING";

  /** The assistant answered and its answer was applied. */
  public static final String       AGENT_DONE             = "DONE";

  /** The assistant failed, after its attempts. */
  public static final String       AGENT_FAILED           = "FAILED";

  /** Not run: the owner's pending or daily cap was reached. */
  public static final String       AGENT_SKIPPED_CAP      = "SKIPPED_CAP";

  /** Not run: assistants are switched off for filters. */
  public static final String       AGENT_SKIPPED_DISABLED = "SKIPPED_DISABLED";

  /** Every assistant status. */
  public static final List<String> AGENT_STATUSES         = List.of(AGENT_NONE,
                                                                    AGENT_PENDING,
                                                                    AGENT_RUNNING,
                                                                    AGENT_DONE,
                                                                    AGENT_FAILED,
                                                                    AGENT_SKIPPED_CAP,
                                                                    AGENT_SKIPPED_DISABLED);

  /** The statuses after which the assistant will not run again by itself. */
  public static final List<String> AGENT_TERMINAL         = List.of(AGENT_DONE,
                                                                    AGENT_FAILED,
                                                                    AGENT_SKIPPED_CAP,
                                                                    AGENT_SKIPPED_DISABLED);

  /** The post-actions wait for the assistant. */
  public static final String       POST_PENDING_AGENT     = "PENDING_AGENT";

  /** The post-actions ran. */
  public static final String       POST_DONE              = "DONE";

  /** The match's id. */
  private Long                     id;

  /** The rule that matched. */
  private Long                     filterId;

  /** The rule's name; null when the rule was deleted since. */
  private String                   filterName;

  /** The mail's Message-ID. */
  private String                   mailHeaderId;

  /** The mail's UID when it matched. */
  private Long                     mailRemoteId;

  /** The folder the mail was in when it matched. */
  private String                   folder;

  /** The mail's subject. */
  private String                   subject;

  /** When it matched, in milliseconds. */
  private Long                     matchedDate;

  /** What was done, and what undoing it needs. */
  private List<AppliedAction>      actions;

  /** {@link #POST_PENDING_AGENT} or {@link #POST_DONE}. */
  private String                   postActionsState;

  /** One of {@link #AGENT_STATUSES}. */
  private String                   agentStatus;

  /** The assistant, for a rule that runs one. */
  private String                   agentNameId;

  /** The assistant's conversation, to open it in the chat. */
  private String                   agentConversationId;

  /** The assistant's answer, as its handler recorded it. */
  private String                   agentOutput;

  /** When the assistant last ran, in milliseconds. */
  private Long                     agentDate;

  /** How many times the assistant ran. */
  private int                      agentAttempts;

  /** The last error, a message code or the handler's short reason. */
  private String                   lastError;
}
