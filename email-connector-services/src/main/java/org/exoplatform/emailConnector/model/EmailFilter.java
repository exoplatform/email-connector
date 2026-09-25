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
 * One rule eXo runs itself, after each sync of the owner's own inbox: the eXo group of
 * the filters drawer. A rule of kind {@link #KIND_HOP} also runs at delivery: its server
 * half, published by eXo, sets {@link #tagKeyword} on the mails it matches, and eXo picks
 * those up at the next sync and checks {@link #conditions} again before acting.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailFilter {

  /** Conditions evaluated by eXo, on the mail the sync just cached. */
  public static final String      KIND_EXO    = "EXO";

  /** The eXo half of a rule that also runs at delivery: triggered by its keyword. */
  public static final String      KIND_HOP    = "HOP";

  /**
   * A server rule, previewed only: never stored in eXo, whose copy of a server rule
   * would drift from what the server runs.
   */
  public static final String      KIND_SERVER = "SERVER";

  /** The kinds a stored rule may have. */
  public static final List<String> KINDS      = List.of(KIND_EXO, KIND_HOP);

  /** The owner's own mailbox, the only scope in this phase. */
  public static final String      SCOPE_OWN   = "OWN";

  /** The rule's id; null before it is created. */
  private Long                     id;

  /** The name the owner gave it. */
  private String                   name;

  /** Whether it runs. */
  private boolean                  enabled;

  /** Its place among the owner's eXo rules, from 0. */
  private int                      position;

  /** {@link #KIND_EXO} or {@link #KIND_HOP}. */
  private String                   kind;

  /** The mailbox it runs on, {@link #SCOPE_OWN}. */
  private String                   mailboxScope;

  /** True when every condition must hold, false when any one is enough. */
  private boolean                  matchAll;

  /** The conditions, in the vocabulary of {@link ServerRule.Condition}. */
  private List<ServerRule.Condition> conditions;

  /** The actions: the assistant, and the post-actions. */
  private List<FilterAction>       actions;

  /** Whether a mail it matched is left alone by the eXo rules after it. */
  private boolean                  stopProcessing;

  /** The keyword the server half sets, for a {@link #KIND_HOP} rule. */
  private String                   tagKeyword;

  /** The server half's reference, for a {@link #KIND_HOP} rule. */
  private String                   serverRuleRef;

  /** The assistant its {@link FilterAction#AGENT} action runs, if any. */
  private String                   agentNameId;

  /** How many mails it matched. */
  private long                     matchCount;

  /** When it last matched, in milliseconds; null when never. */
  private Long                     lastMatchDate;

  /** Why the sync switched it off; null when it did not. */
  private String                   lastError;

  /**
   * When it was created or last switched back on, in milliseconds: the sync runs it on
   * mail received since. Set by the service, never by the form.
   */
  private Long                     activeSince;

  /** When it was created, in milliseconds. */
  private Long                     createdDate;

  /** When it was last saved, in milliseconds. */
  private Long                     updatedDate;

  /**
   * Whether this rule runs an assistant.
   *
   * @return true when one of its actions is {@link FilterAction#AGENT}
   */
  public boolean hasAgent() {
    return actions != null && actions.stream().anyMatch(action -> FilterAction.AGENT.equals(action.type()));
  }

  /**
   * Whether this rule takes a mail it matched out of the inbox.
   *
   * @return true when one of its actions files the mail
   */
  public boolean files() {
    return actions != null && actions.stream().anyMatch(FilterAction::files);
  }
}
