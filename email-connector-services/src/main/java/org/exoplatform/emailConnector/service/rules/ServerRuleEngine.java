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
package org.exoplatform.emailConnector.service.rules;

import java.util.List;

import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.HopRef;
import org.exoplatform.emailConnector.model.ReconcileReport;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerVacation;
import org.exoplatform.emailConnector.model.VacationSetting;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;

/**
 * How eXo manages what a mail server does at delivery for one user -- the automatic
 * reply, and the server rules -- on one family of servers. One interface for both
 * features, chosen per connector preset by {@link ServerRuleEngineRegistry}.
 * <p>
 * Every verb takes the caller's own {@link MailboxAclSession}: the engine opens the
 * transport it needs with the material the session resolves for the caller, and acts as
 * the caller and nobody else -- no administrator key, no mailbox named by a request. A
 * shared mailbox is never reachable through it.
 * <p>
 * What the server can do is answered by {@link #probe}, decided from what the server
 * answers after the engine's own secure handshake, never from an IMAP capability. The
 * rules verbs are declared here from the first consumer on, and answer
 * {@link ServerRuleUnsupportedException} until the server-rules eXip implements them;
 * an engine never throws {@link UnsupportedOperationException}.
 */
public interface ServerRuleEngine {

  /**
   * The name a connector preset selects this engine by, in
   * {@code email.connector.rulesEngine[.<connectorId>]}.
   *
   * @return the name, lower-case
   */
  String getName();

  /**
   * What the server can do for the caller: the engine level, one answer per form element,
   * and whether a publish could meet another client's active state.
   *
   * @param session the caller's own session
   * @return the capabilities, never null
   * @throws ServerRuleUnavailableException when the server cannot be used for this
   *           request
   */
  ServerRuleCapabilities probe(MailboxAclSession session) throws ServerRuleUnavailableException;

  /**
   * Reads the caller's automatic reply as the server holds it.
   *
   * @param session the caller's own session
   * @return what the server holds; {@link ServerVacation#none()} when nothing
   * @throws ServerRuleUnavailableException when the server cannot be used for this
   *           request
   */
  ServerVacation readVacation(MailboxAclSession session) throws ServerRuleUnavailableException;

  /**
   * Writes the caller's automatic reply. A reply switched off keeps its text on the
   * server, so switching it back on needs no copy in eXo.
   *
   * @param session the caller's own session
   * @param vacation the reply, validated by the caller
   * @param days the minimum number of days between two replies to one sender
   * @param expectedScriptHash the SHA-256 of eXo's script as eXo last wrote it; when not
   *          null and the server holds something else, nothing is written. Null to
   *          overwrite eXo's own script whatever it holds ("Re-publish")
   * @return what the server holds after the write
   * @throws ServerRuleUnavailableException when the server cannot be used for this
   *           request
   * @throws ServerRuleConflictException when writing would replace, rewrite or break
   *           another client's state, or eXo's script changed outside eXo; nothing was
   *           written
   * @throws ServerRuleUnsupportedException when this engine or server cannot hold a reply
   */
  ServerVacation writeVacation(MailboxAclSession session,
                               VacationSetting vacation,
                               int days,
                               String expectedScriptHash) throws ServerRuleUnavailableException,
                                                          ServerRuleConflictException,
                                                          ServerRuleUnsupportedException;

  /**
   * The caller's server rules, in the order the server applies them.
   *
   * @param session the caller's own session
   * @return the rules
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleUnsupportedException until an engine implements server rules
   */
  default List<ServerRule> listRules(MailboxAclSession session) throws ServerRuleUnavailableException,
                                                                ServerRuleUnsupportedException {
    throw new ServerRuleUnsupportedException(ServerRuleUnsupportedException.RULES_UNSUPPORTED);
  }

  /**
   * Creates or replaces one of the caller's server rules.
   *
   * @param session the caller's own session
   * @param rule the rule
   * @return the rule as saved
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when another client's active state is in the way
   * @throws ServerRuleUnsupportedException until an engine implements server rules
   */
  default ServerRule saveRule(MailboxAclSession session, ServerRule rule) throws ServerRuleUnavailableException,
                                                                          ServerRuleConflictException,
                                                                          ServerRuleUnsupportedException {
    throw new ServerRuleUnsupportedException(ServerRuleUnsupportedException.RULES_UNSUPPORTED);
  }

  /**
   * Deletes one of the caller's server rules.
   *
   * @param session the caller's own session
   * @param ref the rule's reference
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when another client's active state is in the way
   * @throws ServerRuleUnsupportedException until an engine implements server rules
   */
  default void deleteRule(MailboxAclSession session, String ref) throws ServerRuleUnavailableException,
                                                                 ServerRuleConflictException,
                                                                 ServerRuleUnsupportedException {
    throw new ServerRuleUnsupportedException(ServerRuleUnsupportedException.RULES_UNSUPPORTED);
  }

  /**
   * Makes the server hold exactly the hops eXo's own rules need.
   *
   * @param session the caller's own session
   * @param hops the hops to keep
   * @return what was done
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when another client's active state is in the way
   * @throws ServerRuleUnsupportedException until an engine implements server rules
   */
  default ReconcileReport reconcile(MailboxAclSession session, List<HopRef> hops) throws ServerRuleUnavailableException,
                                                                                  ServerRuleConflictException,
                                                                                  ServerRuleUnsupportedException {
    throw new ServerRuleUnsupportedException(ServerRuleUnsupportedException.RULES_UNSUPPORTED);
  }
}
