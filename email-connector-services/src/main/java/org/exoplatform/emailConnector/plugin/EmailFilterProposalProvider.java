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
package org.exoplatform.emailConnector.plugin;

import java.util.Collection;
import java.util.List;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.model.EmailFilter;
import org.exoplatform.emailConnector.model.EmailFilterProposal;
import org.exoplatform.emailConnector.model.EmailFilterProposalCount;

/**
 * Where the suggestions of the mail filters' assistant are kept and decided, and what
 * hears of the filters' lives (EXO-90956): the AI add-on's shared proposals service, in
 * a deployment that has it. A bean implementing this interface in the email-connector
 * Spring context answers for it; the email-connector keeps no store of its own, and its
 * own REST, "Suggestions" view and Automations panel read and decide through it. Without
 * such a bean -- no AI add-on, or its profile off -- nothing is suggested, the lists are
 * empty and a decision is refused.
 * <p>
 * Every call names the owner, who decides the suggestions and gave the rules their
 * standing approvals; the email-connector has checked that the request is theirs, from
 * their own mailbox, before it calls. The first bean in order answers.
 */
public interface EmailFilterProposalProvider {

  /**
   * The suggestions of some of the owner's matches, the expired ones marked so first:
   * the cards of a mail's Automations panel.
   *
   * @param ownerName the owner
   * @param matchIds the matches
   * @return the suggestions, oldest first
   */
  List<EmailFilterProposal> getProposalsOfMatches(String ownerName, Collection<Long> matchIds);

  /**
   * @param ownerName the owner
   * @return the owner's matches with a suggestion still waiting, each once per waiting
   *         suggestion
   */
  List<Long> getWaitingMatchIds(String ownerName);

  /**
   * @param ownerName the owner
   * @return how many suggestions each of the owner's matches holds, by status
   */
  List<EmailFilterProposalCount> countByMatch(String ownerName);

  /**
   * Approves one of the owner's suggestions and runs it now, as the owner.
   *
   * @param ownerName the owner, from the request's session
   * @param id the suggestion
   * @param allowForFilter whether its rule may run the tool without asking from now on
   * @return the suggestion once run
   * @throws ObjectNotFoundException when there is no such suggestion
   * @throws IllegalAccessException when it is not the owner's
   * @throws IllegalStateException with a message code when it no longer waits
   */
  EmailFilterProposal approve(String ownerName, long id, boolean allowForFilter) throws ObjectNotFoundException,
                                                                                 IllegalAccessException;

  /**
   * Rejects one of the owner's suggestions: it never runs.
   *
   * @param ownerName the owner, from the request's session
   * @param id the suggestion
   * @return the suggestion, rejected
   * @throws ObjectNotFoundException as {@link #approve}
   * @throws IllegalAccessException as {@link #approve}
   */
  EmailFilterProposal reject(String ownerName, long id) throws ObjectNotFoundException, IllegalAccessException;

  /**
   * Hands one of the owner's suggestions to the AI chat: it never runs from here again.
   *
   * @param ownerName the owner, from the request's session
   * @param id the suggestion
   * @return the suggestion, handed over
   * @throws ObjectNotFoundException as {@link #approve}
   * @throws IllegalAccessException as {@link #approve}
   */
  EmailFilterProposal handOver(String ownerName, long id) throws ObjectNotFoundException, IllegalAccessException;

  /**
   * Hears that a rule of the owner's was saved, by its owner's request, once the write
   * is done: switched on or off, its actions changed. A rule switched off by its owner
   * no longer runs anything without asking; one still running its assistant keeps its
   * approvals, renewed. Must not throw.
   *
   * @param ownerName the owner
   * @param previous the rule as it was, null for a new one
   * @param saved the rule as saved
   */
  default void onFilterSaved(String ownerName, EmailFilter previous, EmailFilter saved) {
    // nothing by default
  }

  /**
   * Hears that a rule of the owner's was deleted, once the write is done. Must not
   * throw.
   *
   * @param ownerName the owner
   * @param filterId the rule
   */
  default void onFilterDeleted(String ownerName, long filterId) {
    // nothing by default
  }

  /**
   * Hears that the owner's mailbox is disconnected: their rules run nothing until it
   * is connected again. Must not throw.
   *
   * @param ownerName the owner
   */
  default void onMailboxDisconnected(String ownerName) {
    // nothing by default
  }

  /**
   * Hears that matches of the owner's were deleted past their retention: their decided
   * suggestions go with them. Must not throw.
   *
   * @param ownerName the owner
   * @param matchIds the deleted matches
   */
  default void onMatchesPurged(String ownerName, Collection<Long> matchIds) {
    // nothing by default
  }

}
