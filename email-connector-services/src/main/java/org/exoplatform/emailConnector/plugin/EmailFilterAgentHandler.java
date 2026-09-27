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

import org.exoplatform.emailConnector.model.EmailFilterProposal;

/**
 * The declaration that something answers the mail filters' assistant request: a bean
 * implementing this interface in the email-connector Spring context declares that a
 * listener answers {@link org.exoplatform.emailConnector.utils.EmailConnectorUtils#FILTER_AGENT_REQUESTED}
 * and runs the assistant of the matches it names, then calls back
 * {@link org.exoplatform.emailConnector.service.EmailFilterService#applyPostActions}.
 * <p>
 * The request goes out through the kernel {@code ListenerService}, which says nothing of
 * who listens, so the glue that listens says it here as well. Without such a bean -- no
 * AI add-on, or its profile off --
 * {@link org.exoplatform.emailConnector.service.EmailFilterService} does not queue the
 * assistant: a match is recorded as skipped and the rule's other actions run at once,
 * rather than wait for an answer that never comes.
 * <p>
 * The same bean runs the tool calls the assistant proposed once their owner approves
 * them ({@link #executeProposal}): the email-connector records and guards the proposals,
 * the glue alone knows the platform's tools.
 */
public interface EmailFilterAgentHandler {

  /**
   * Runs one approved tool call, as its owner, through the platform's own tool path --
   * the one the AI chat uses, with the tool's own permission checks and approval. Called
   * once the proposal is claimed {@code RUNNING} for this owner, and never otherwise.
   *
   * @param username the owner, who approved it
   * @param proposal the proposal, {@code RUNNING}
   * @return what the tool answered
   * @throws Exception the tool's refusal or failure, whose message the owner reads; the
   *           default, for a handler that runs no tool, refuses every call
   */
  default String executeProposal(String username, EmailFilterProposal proposal) throws Exception { // NOSONAR the tool's own
    throw new UnsupportedOperationException("emailConnector.filters.proposal.unavailable");
  }
}
