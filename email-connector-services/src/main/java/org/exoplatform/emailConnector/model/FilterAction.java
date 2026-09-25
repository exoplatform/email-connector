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
import java.util.Set;

/**
 * One action of an eXo rule: the assistant, or a post-action eXo applies itself, as the
 * owner, through the mailbox's own ACL-checked methods.
 *
 * @param type one of {@link #TYPES}
 * @param folderKey for {@link #MOVE_TO_FOLDER}: the eXo key of one of the owner's own
 *          mirrored folders ({@code CUSTOM:<id>}) or {@code ARCHIVE}
 * @param categoryId for {@link #ADD_CATEGORY}: one of the owner's available categories
 * @param agentNameId for {@link #AGENT}: the assistant, by its name id
 * @param instruction for {@link #AGENT}: what the owner asks of it
 * @param outputs for {@link #AGENT}: what its answer may change, a subset of
 *          {@link #AGENT_OUTPUTS}
 * @param when {@link #IMMEDIATE} or {@link #AFTER_AGENT}; decided by the service, not
 *          the form: every post-action of a rule with an assistant waits for it
 */
public record FilterAction(String type,
                           String folderKey,
                           Long categoryId,
                           String agentNameId,
                           String instruction,
                           List<String> outputs,
                           String when) {

  /** Runs an assistant on the mail; its answer is applied by the enterprise glue. */
  public static final String      AGENT          = "AGENT";

  /** Files the mail into one of the owner's folders. */
  public static final String      MOVE_TO_FOLDER = ServerRule.MOVE_TO_FOLDER;

  /** Puts one of the owner's categories on the mail. */
  public static final String      ADD_CATEGORY   = "ADD_CATEGORY";

  /** Marks the mail read. */
  public static final String      MARK_READ      = ServerRule.MARK_READ;

  /** Stars the mail. */
  public static final String      STAR           = ServerRule.STAR;

  /** Files the mail into Junk. */
  public static final String      MARK_JUNK      = ServerRule.MARK_JUNK;

  /** Files the mail into Trash, from where it can be restored. */
  public static final String      DELETE         = ServerRule.DELETE;

  /** Notifies the owner. */
  public static final String      NOTIFY         = "NOTIFY";

  /** Applied in the pass that matched the mail. */
  public static final String      IMMEDIATE      = "IMMEDIATE";

  /** Applied once the assistant is done with the mail, whatever became of its run. */
  public static final String      AFTER_AGENT    = "AFTER_AGENT";

  /** Every action type, in the form's order. */
  public static final List<String> TYPES         = List.of(AGENT,
                                                            MOVE_TO_FOLDER,
                                                            ADD_CATEGORY,
                                                            MARK_READ,
                                                            STAR,
                                                            MARK_JUNK,
                                                            DELETE,
                                                            NOTIFY);

  /** The actions that take the mail out of the inbox; a rule has at most one. */
  public static final Set<String>  FILING        = Set.of(MOVE_TO_FOLDER, MARK_JUNK, DELETE);

  /** What an assistant's answer may change, when the rule allows it. */
  public static final List<String> AGENT_OUTPUTS = List.of("NOTE", "CATEGORY", "STAR", "MARK_READ", "DRAFT_REPLY", "NOTIFY");

  /**
   * Keeps the outputs unmodifiable, and never null.
   *
   * @param type the type
   * @param folderKey the folder
   * @param categoryId the category
   * @param agentNameId the assistant
   * @param instruction the instruction
   * @param outputs the outputs
   * @param when when it applies
   */
  public FilterAction {
    outputs = outputs == null ? List.of() : List.copyOf(outputs);
  }

  /**
   * The same action, applied at another moment.
   *
   * @param newWhen {@link #IMMEDIATE} or {@link #AFTER_AGENT}
   * @return the action
   */
  public FilterAction withWhen(String newWhen) {
    return new FilterAction(type, folderKey, categoryId, agentNameId, instruction, outputs, newWhen);
  }

  /**
   * Whether this action takes the mail out of the inbox.
   *
   * @return true for a move, a move to Junk or to Trash
   */
  public boolean files() {
    return FILING.contains(type);
  }
}
