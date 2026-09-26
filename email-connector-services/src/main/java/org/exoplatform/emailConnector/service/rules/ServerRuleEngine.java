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

import java.time.ZoneId;
import java.util.List;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.ForwardingSetting;
import org.exoplatform.emailConnector.model.HopRef;
import org.exoplatform.emailConnector.model.ReconcileReport;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRuleSet;
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
 * rules verbs answer {@link ServerRuleUnsupportedException} for an engine that does not
 * implement them; an engine never throws {@link UnsupportedOperationException}.
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
   * Reads the caller's automatic reply as the server holds it, its days stated in the
   * given zone. An engine whose server stores the reply's window as instants (BlueMind)
   * needs the zone to answer calendar days; one that stores the days and their zone
   * itself (Sieve, in eXo's header) ignores it.
   *
   * @param session the caller's own session
   * @param zone the zone to state the days in, the caller's own; null when unknown
   * @return what the server holds; {@link ServerVacation#none()} when nothing
   * @throws ServerRuleUnavailableException when the server cannot be used for this
   *           request
   */
  default ServerVacation readVacation(MailboxAclSession session, ZoneId zone) throws ServerRuleUnavailableException {
    return readVacation(session);
  }

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
   * Reads whether the caller's mailbox forwards mail, as the server holds it. <b>A read
   * only</b>: no engine writes a forward in this phase, and this verb never issues a
   * write to the server. An engine that holds forwards structurally names the
   * destinations; one that only sees another client's scripts says which script may
   * forward, and never reads destinations out of it.
   *
   * @param session the caller's own session
   * @return what could be established; {@link ForwardingSetting#unknown()} for an engine
   *         that cannot read a forward, which is the default
   * @throws ServerRuleUnavailableException when the server cannot be used for this
   *           request
   */
  default ForwardingSetting readForwarding(MailboxAclSession session) throws ServerRuleUnavailableException {
    return ForwardingSetting.unknown();
  }

  /**
   * The server rules eXo manages for the caller, in the order the server applies them,
   * with where they stand. An engine that cannot read rules another client wrote answers
   * eXo's own only, and names the script those live in.
   *
   * @param session the caller's own session
   * @return the rules; {@link ServerRuleSet#none()} when eXo manages none
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleUnsupportedException when this engine does not implement server
   *           rules
   */
  default ServerRuleSet listRules(MailboxAclSession session) throws ServerRuleUnavailableException,
                                                             ServerRuleUnsupportedException {
    throw new ServerRuleUnsupportedException(ServerRuleUnsupportedException.RULES_UNSUPPORTED);
  }

  /**
   * Creates or replaces one of the caller's server rules, and publishes. A rule without
   * a reference is appended under a new one; a rule with a reference replaces the rule
   * holding it, in place. Hops are not written here: {@link #reconcile} owns them.
   * Whatever else the
   * server holds for the caller -- the automatic reply, the other rules, another
   * client's scripts -- is kept as it is.
   *
   * @param session the caller's own session
   * @param rule the rule, its folders resolved, validated again by the engine
   * @param expectedScriptHash the SHA-256 of eXo's script as eXo last wrote it; when not
   *          null and the server holds something else, nothing is written. Null to
   *          overwrite eXo's own script whatever it holds ("Re-publish")
   * @return the rules as the server holds them after the write
   * @throws ObjectNotFoundException when the reference names no rule of eXo's
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when another client's active state is in the way,
   *           or eXo's script changed outside eXo; nothing was written
   * @throws ServerRuleUnsupportedException when this engine or server cannot express the
   *           rule
   */
  default ServerRuleSet saveRule(MailboxAclSession session,
                                 ServerRule rule,
                                 String expectedScriptHash) throws ObjectNotFoundException,
                                                            ServerRuleUnavailableException,
                                                            ServerRuleConflictException,
                                                            ServerRuleUnsupportedException {
    throw new ServerRuleUnsupportedException(ServerRuleUnsupportedException.RULES_UNSUPPORTED);
  }

  /**
   * Deletes one of the caller's server rules, and publishes. A hop is not deleted here:
   * {@link #reconcile} owns them.
   *
   * @param session the caller's own session
   * @param ref the rule's reference
   * @param expectedScriptHash as for {@link #saveRule}
   * @return the rules as the server holds them after the write
   * @throws ObjectNotFoundException when no rule of eXo's holds the reference
   * @throws IllegalArgumentException when the reference names a hop
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when another client's active state is in the way,
   *           or eXo's script changed outside eXo; nothing was written
   * @throws ServerRuleUnsupportedException when this engine does not implement server
   *           rules
   */
  default ServerRuleSet deleteRule(MailboxAclSession session,
                                   String ref,
                                   String expectedScriptHash) throws ObjectNotFoundException,
                                                              ServerRuleUnavailableException,
                                                              ServerRuleConflictException,
                                                              ServerRuleUnsupportedException {
    throw new ServerRuleUnsupportedException(ServerRuleUnsupportedException.RULES_UNSUPPORTED);
  }

  /**
   * Writes eXo's rules as the server holds them, unchanged, and makes the server run
   * them: "Re-activate" after another client activated its own script, or "Re-publish"
   * after eXo's script was edited outside eXo (with a null hash).
   *
   * @param session the caller's own session
   * @param expectedScriptHash as for {@link #saveRule}
   * @return the rules as the server holds them after the write
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when another client's active state is in the way,
   *           or eXo's script changed outside eXo; nothing was written
   * @throws ServerRuleUnsupportedException when this engine does not implement server
   *           rules
   */
  default ServerRuleSet publishRules(MailboxAclSession session,
                                     String expectedScriptHash) throws ServerRuleUnavailableException,
                                                                ServerRuleConflictException,
                                                                ServerRuleUnsupportedException {
    throw new ServerRuleUnsupportedException(ServerRuleUnsupportedException.RULES_UNSUPPORTED);
  }

  /**
   * Makes the server hold exactly the hops eXo's own rules need: a hop missing or
   * different is published, a hop no eXo rule needs any more is removed, every other
   * rule is kept as it is. Nothing is written when nothing differs.
   *
   * @param session the caller's own session
   * @param hops the hops to keep
   * @param expectedScriptHash as for {@link #saveRule}
   * @return what was done, and the rules afterwards
   * @throws ServerRuleUnavailableException when the server cannot be used
   * @throws ServerRuleConflictException when another client's active state is in the way,
   *           or eXo's script changed outside eXo; nothing was written
   * @throws ServerRuleUnsupportedException when this engine or server cannot express a
   *           hop
   */
  default ReconcileReport reconcile(MailboxAclSession session,
                                    List<HopRef> hops,
                                    String expectedScriptHash) throws ServerRuleUnavailableException,
                                                               ServerRuleConflictException,
                                                               ServerRuleUnsupportedException {
    throw new ServerRuleUnsupportedException(ServerRuleUnsupportedException.RULES_UNSUPPORTED);
  }
}
